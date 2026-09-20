// ══════════════════════════════════════════════════════════════════════════
//  우리교회 · Cloudflare Workers API
//  D1(데이터) + R2(주보 파일). 안드로이드 앱이 이 API 를 호출합니다.
// ══════════════════════════════════════════════════════════════════════════

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type,Authorization,X-File-Name',
};

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...CORS },
  });

const fail = (message, status = 400) => json({ error: message }, status);

const TITLES = ['목사', '사모', '장로', '안수집사', '권사', '집사', '성도'];
const ROLES = ['owner', 'admin', 'leader', 'member'];
const SCOPES = ['own_cell', 'all_cells'];

// ── 암호 ────────────────────────────────────────────────────────────────
const enc = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

function randomHex(bytes = 32) {
  return hex(crypto.getRandomValues(new Uint8Array(bytes)));
}

async function derive(secret, salt) {
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    // Cloudflare Workers 는 반복 10만까지만 허용합니다
    { name: 'PBKDF2', salt: enc.encode(salt), iterations: 100000, hash: 'SHA-256' },
    key,
    256
  );
  return hex(bits);
}

// 타이밍 공격 방지용 비교
function sameString(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

const nowIso = () => new Date().toISOString().replace('T', ' ').slice(0, 19);
const today = () => new Date().toISOString().slice(0, 10);

// ── 인증 ────────────────────────────────────────────────────────────────
async function currentMember(req, env) {
  const auth = req.headers.get('Authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
  if (!token) return null;
  const row = await env.DB.prepare(
    `select m.*, c.name as church_name, c.status as church_status, c.zoom_url,
            cl.name as cell_name
       from sessions s
       join members  m on m.id = s.member_id
       join churches c on c.id = m.church_id
       left join cells cl on cl.id = m.cell_id
      where s.token = ?`
  ).bind(token).first();
  if (!row) return null;
  if (row.status !== 'active') return null;
  if (row.church_status !== 'approved') return null;
  await env.DB.prepare('update sessions set last_seen = ? where token = ?').bind(nowIso(), token).run();
  return row;
}

const canManage = (me) => me.role === 'owner' || me.role === 'admin';
const isOwner = (me) => me.role === 'owner';
const canShareAnywhere = (me) => me.share_scope === 'all_cells';

async function audit(env, me, action, target, detail) {
  await env.DB.prepare(
    `insert into audit_log(church_id, actor_id, actor_name, action, target_member, target_name, detail)
     values(?,?,?,?,?,?,?)`
  ).bind(me.church_id, me.id, me.name, action, target?.id ?? null, target?.name ?? null, detail ?? null).run();
}

// 내가 볼 수 있는 셀 목록 (본인 셀 하나, 또는 전체)
async function visibleCellIds(env, me, scope) {
  if (scope === 'all') {
    const r = await env.DB.prepare('select id from cells where church_id = ?').bind(me.church_id).all();
    return r.results.map((x) => x.id);
  }
  return me.cell_id ? [me.cell_id] : [];
}

// ══════════════════════════════════════════════════════════════════════════
export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    try {
      return await route(req, env, url, path);
    } catch (e) {
      return fail('서버 오류: ' + (e?.message || e), 500);
    }
  },
};

async function body(req) {
  try { return await req.json(); } catch { return {}; }
}

async function route(req, env, url, path) {
  const P = (s) => path === '/api' + s;
  const q = (k) => url.searchParams.get(k);

  if (path === '/' || path === '/api') return json({ ok: true, name: '우리교회 API' });

  // ─────────────────────────────────────────────── 교회 찾기 · 등록 (로그인 전)
  if (P('/churches/search')) {
    const term = (q('q') || '').trim();
    if (term.length < 1) return json({ churches: [] });
    const r = await env.DB.prepare(
      `select id, name, address, denomination from churches
        where status = 'approved' and (name like ?1 or address like ?1)
        order by name limit 30`
    ).bind('%' + term + '%').all();
    return json({ churches: r.results });
  }

  if (P('/churches/register') && req.method === 'POST') {
    const b = await body(req);
    const need = ['churchName', 'address', 'phone', 'denomination', 'pastorName', 'pinNumber'];
    for (const k of need) if (!b[k] || String(b[k]).trim() === '') return fail('빠진 항목이 있습니다: ' + k);
    if (!/^\d{6}$/.test(b.pinNumber)) return fail('비밀번호는 숫자 6자리여야 합니다');

    // 1단 — 이단 규정 단체 키워드 자동 거부
    const blocked = await env.DB.prepare('select keyword, note from blocked_keywords').all();
    const haystack = [b.churchName, b.denomination, b.pastorName, b.homepage, b.note].join(' ');
    for (const row of blocked.results) {
      if (haystack.includes(row.keyword)) {
        // 사유는 신청자에게 알리지 않습니다 (명예훼손 시비 방지)
        return fail('신청이 접수되지 않았습니다. 교회 정보를 다시 확인해 주세요.', 422);
      }
    }

    // 초대 코드가 있으면 심사 간소화 대상으로 표시
    let invitedBy = null;
    if (b.inviteCode) {
      const inv = await env.DB.prepare(
        `select id from churches where invite_code = ? and status = 'approved'`
      ).bind(b.inviteCode.trim()).first();
      if (!inv) return fail('초대 코드가 올바르지 않습니다');
      invitedBy = inv.id;
    }

    const dup = await env.DB.prepare(
      `select id from churches where name = ? and address = ? and status in ('pending','approved')`
    ).bind(b.churchName.trim(), b.address.trim()).first();
    if (dup) return fail('이미 신청되었거나 등록된 교회입니다');

    // 비밀번호를 먼저 만듭니다. 여기서 실패하면 교회 줄이 남지 않도록.
    if (b.email && b.password && String(b.password).length < 8)
      return fail('관리자 비밀번호는 8자 이상이어야 합니다');
    const salt = randomHex(16);
    const pinHash = await derive(b.pinNumber, salt);
    let pwHash = null, pwSalt = null;
    if (b.email && b.password) {
      pwSalt = randomHex(16);
      pwHash = await derive(b.password, pwSalt);
    }

    const ch = await env.DB.prepare(
      `insert into churches(name,address,phone,denomination,pastor_name,seminary,homepage,note,invited_by)
       values(?,?,?,?,?,?,?,?,?) returning id`
    ).bind(
      b.churchName.trim(), b.address.trim(), b.phone.trim(), b.denomination.trim(),
      b.pastorName.trim(), b.seminary || null, b.homepage || null, b.note || null, invitedBy
    ).first();

    try {
      await env.DB.prepare(
        `insert into members(church_id,name,pin_hash,pin_salt,email,pw_hash,pw_salt,title,role,share_scope,status)
         values(?,?,?,?,?,?,?, '목사','owner','all_cells','active')`
      ).bind(ch.id, b.pastorName.trim(), pinHash, salt, b.email || null, pwHash, pwSalt).run();
    } catch (e) {
      // 계정을 못 만들면 교회 줄도 되돌립니다 (주인 없는 교회가 남지 않도록)
      await env.DB.prepare('delete from churches where id = ?').bind(ch.id).run();
      throw e;
    }

    return json({ ok: true, status: 'pending', message: '등록 신청이 접수되었습니다. 승인까지 보통 1~2일 걸립니다.' });
  }

  // ─────────────────────────────────────────────── 성도 가입 · 로그인
  if (P('/members/signup') && req.method === 'POST') {
    const b = await body(req);
    if (!b.churchId || !b.name || !/^\d{6}$/.test(b.pinNumber || ''))
      return fail('교회, 이름, 숫자 6자리 비밀번호가 필요합니다');
    const ch = await env.DB.prepare(`select id from churches where id = ? and status='approved'`).bind(b.churchId).first();
    if (!ch) return fail('교회를 찾을 수 없습니다');
    const dup = await env.DB.prepare('select id, status from members where church_id = ? and name = ?')
      .bind(b.churchId, b.name.trim()).first();
    if (dup) return fail('이미 같은 이름으로 신청되어 있습니다. 목사님께 문의해 주세요.');
    const salt = randomHex(16);
    await env.DB.prepare(
      `insert into members(church_id,name,pin_hash,pin_salt,status) values(?,?,?,?, 'pending')`
    ).bind(b.churchId, b.name.trim(), await derive(b.pinNumber, salt), salt).run();
    return json({ ok: true, status: 'pending', message: '가입 신청이 접수되었습니다. 목사님 승인 후 이용할 수 있습니다.' });
  }

  if (P('/auth/login') && req.method === 'POST') {
    const b = await body(req);
    let row;
    if (b.email) {
      row = await env.DB.prepare('select * from members where email = ?').bind(String(b.email).trim()).first();
    } else {
      if (!b.churchId || !b.name) return fail('교회와 이름이 필요합니다');
      row = await env.DB.prepare('select * from members where church_id = ? and name = ?')
        .bind(b.churchId, String(b.name).trim()).first();
    }
    if (!row) return fail('이름 또는 비밀번호가 맞지 않습니다', 401);
    if (row.locked_until && row.locked_until > nowIso())
      return fail('비밀번호를 여러 번 틀렸습니다. 잠시 후 다시 시도하거나 목사님께 초기화를 요청하세요.', 423);

    const secret = b.email ? b.password : b.pinNumber;
    const salt = b.email ? row.pw_salt : row.pin_salt;
    const want = b.email ? row.pw_hash : row.pin_hash;
    if (!secret || !salt || !want) return fail('이름 또는 비밀번호가 맞지 않습니다', 401);
    const got = await derive(String(secret), salt);
    if (!sameString(got, want)) {
      const n = row.fail_count + 1;
      const lock = n >= 5 ? new Date(Date.now() + 10 * 60000).toISOString().replace('T', ' ').slice(0, 19) : null;
      await env.DB.prepare('update members set fail_count = ?, locked_until = ? where id = ?')
        .bind(n >= 5 ? 0 : n, lock, row.id).run();
      return fail(n >= 5
        ? '비밀번호를 5번 틀려 10분간 잠겼습니다.'
        : `이름 또는 비밀번호가 맞지 않습니다. (${5 - n}번 남음)`, 401);
    }
    if (row.status === 'pending') return fail('아직 승인 전입니다. 목사님 승인 후 이용할 수 있습니다.', 403);
    if (row.status !== 'active') return fail('사용이 중지된 계정입니다. 목사님께 문의해 주세요.', 403);
    const ch = await env.DB.prepare('select status from churches where id = ?').bind(row.church_id).first();
    if (ch?.status !== 'approved') return fail('교회가 아직 승인되지 않았습니다.', 403);

    await env.DB.prepare('update members set fail_count = 0, locked_until = null where id = ?').bind(row.id).run();
    const token = randomHex(32);
    await env.DB.prepare('insert into sessions(token, member_id) values(?,?)').bind(token, row.id).run();
    return json({ token, mustChangePin: !!row.must_change_pin });
  }

  if (P('/auth/logout') && req.method === 'POST') {
    const auth = req.headers.get('Authorization') || '';
    if (auth.startsWith('Bearer ')) await env.DB.prepare('delete from sessions where token = ?').bind(auth.slice(7)).run();
    return json({ ok: true });
  }

  // ───────────────────────────────────────────── 여기부터는 로그인 필요
  const me = await currentMember(req, env);
  if (!me) return fail('로그인이 필요합니다', 401);

  return await authed(req, env, url, path, me, P, q);
}

// ══════════════════════════════════════════════════════════════════════════
//  로그인한 사용자용
// ══════════════════════════════════════════════════════════════════════════
async function authed(req, env, url, path, me, P, q) {

  // ── 내 정보 ──────────────────────────────────────────────────────────
  if (P('/me')) {
    return json({
      id: me.id, name: me.name, churchId: me.church_id, churchName: me.church_name,
      title: me.title, role: me.role, shareScope: me.share_scope,
      cellId: me.cell_id, cellName: me.cell_name, zoomUrl: me.zoom_url,
      mustChangePin: !!me.must_change_pin,
      isPlatformAdmin: !!me.is_platform_admin,
    });
  }

  if (P('/me/update') && req.method === 'POST') {
    const b = await body(req);
    if (b.name && b.name.trim() !== me.name) {
      const dup = await env.DB.prepare('select id from members where church_id = ? and name = ? and id <> ?')
        .bind(me.church_id, b.name.trim(), me.id).first();
      if (dup) return fail('교회에 같은 이름이 이미 있습니다');
      await env.DB.prepare('update members set name = ? where id = ?').bind(b.name.trim(), me.id).run();
    }
    if (b.newPin) {
      if (!/^\d{6}$/.test(b.newPin)) return fail('비밀번호는 숫자 6자리여야 합니다');
      if (!b.currentPin && !me.must_change_pin) return fail('현재 비밀번호를 입력해 주세요');
      if (!me.must_change_pin) {
        const got = await derive(String(b.currentPin), me.pin_salt);
        if (!sameString(got, me.pin_hash)) return fail('현재 비밀번호가 맞지 않습니다', 401);
      }
      const salt = randomHex(16);
      await env.DB.prepare('update members set pin_hash = ?, pin_salt = ?, must_change_pin = 0 where id = ?')
        .bind(await derive(b.newPin, salt), salt, me.id).run();
    }
    return json({ ok: true });
  }

  // ── 홈 화면 한 번에 ───────────────────────────────────────────────────
  if (P('/home')) {
    const notice = await env.DB.prepare(
      `select n.id, n.title, n.body, n.created_at,
              (select count(*) from notice_reads r where r.notice_id = n.id and r.member_id = ?) as read_flag
         from notices n where n.church_id = ?
         order by n.pinned desc, n.created_at desc limit 1`
    ).bind(me.id, me.church_id).first();
    const unread = await env.DB.prepare(
      `select count(*) as c from notices n
        where n.church_id = ?
          and not exists (select 1 from notice_reads r where r.notice_id = n.id and r.member_id = ?)`
    ).bind(me.church_id, me.id).first();
    const bulletin = await env.DB.prepare(
      `select id, title, week_of, mime, size from bulletins where church_id = ?
        order by week_of desc, id desc limit 1`
    ).bind(me.church_id).first();
    let pending = 0;
    if (canManage(me)) {
      const p = await env.DB.prepare(`select count(*) as c from members where church_id = ? and status = 'pending'`)
        .bind(me.church_id).first();
      pending = p.c;
    }
    const wroteToday = await env.DB.prepare('select id from entries where member_id = ? and date = ?')
      .bind(me.id, today()).first();
    return json({
      zoomUrl: me.zoom_url,
      latestNotice: notice ? { id: notice.id, title: notice.title, body: notice.body, createdAt: notice.created_at, unread: !notice.read_flag } : null,
      unreadNoticeCount: unread.c,
      latestBulletin: bulletin || null,
      pendingApprovals: pending,
      wroteToday: !!wroteToday,
    });
  }

  // ── 셀 ────────────────────────────────────────────────────────────────
  if (P('/cells')) {
    const r = await env.DB.prepare(
      `select c.id, c.name, c.leader_id, l.name as leader_name,
              (select count(*) from members m where m.cell_id = c.id and m.status='active') as member_count
         from cells c left join members l on l.id = c.leader_id
        where c.church_id = ? order by c.name`
    ).bind(me.church_id).all();
    return json({ cells: r.results });
  }

  if (P('/cells/save') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    if (!b.name || !b.name.trim()) return fail('셀 이름을 입력해 주세요');
    if (b.id) {
      await env.DB.prepare('update cells set name = ? where id = ? and church_id = ?')
        .bind(b.name.trim(), b.id, me.church_id).run();
      await audit(env, me, '셀 이름 변경', null, b.name.trim());
    } else {
      await env.DB.prepare('insert into cells(church_id, name) values(?,?)').bind(me.church_id, b.name.trim()).run();
      await audit(env, me, '셀 만들기', null, b.name.trim());
    }
    return json({ ok: true });
  }

  if (P('/cells/leader') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const cell = await env.DB.prepare('select * from cells where id = ? and church_id = ?').bind(b.cellId, me.church_id).first();
    if (!cell) return fail('셀을 찾을 수 없습니다');
    if (cell.leader_id) await env.DB.prepare(`update members set role = 'member' where id = ? and role = 'leader'`).bind(cell.leader_id).run();
    if (b.memberId) {
      const t = await env.DB.prepare('select * from members where id = ? and church_id = ?').bind(b.memberId, me.church_id).first();
      if (!t) return fail('성도를 찾을 수 없습니다');
      await env.DB.prepare('update cells set leader_id = ? where id = ?').bind(b.memberId, b.cellId).run();
      await env.DB.prepare(`update members set cell_id = ?, role = case when role in ('owner','admin') then role else 'leader' end where id = ?`)
        .bind(b.cellId, b.memberId).run();
      await audit(env, me, '셀장 지정', t, cell.name);
    } else {
      await env.DB.prepare('update cells set leader_id = null where id = ?').bind(b.cellId).run();
      await audit(env, me, '셀장 해제', null, cell.name);
    }
    return json({ ok: true });
  }

  if (P('/cells/delete') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const cell = await env.DB.prepare('select * from cells where id = ? and church_id = ?').bind(b.id, me.church_id).first();
    if (!cell) return fail('셀을 찾을 수 없습니다');
    await env.DB.prepare('update members set cell_id = null where cell_id = ?').bind(b.id).run();
    await env.DB.prepare('delete from entry_targets where cell_id = ?').bind(b.id).run();
    await env.DB.prepare('delete from prayer_targets where cell_id = ?').bind(b.id).run();
    await env.DB.prepare('delete from cells where id = ?').bind(b.id).run();
    await audit(env, me, '셀 삭제', null, cell.name);
    return json({ ok: true });
  }

  // ── 성도 관리 ─────────────────────────────────────────────────────────
  if (P('/members')) {
    if (!canManage(me) && me.role !== 'leader') return fail('권한이 없습니다', 403);
    // 셀장은 자기 셀만
    const onlyMyCell = !canManage(me);
    const sql =
      `select m.id, m.name, m.title, m.role, m.share_scope, m.cell_id, m.status, m.created_at,
              c.name as cell_name
         from members m left join cells c on c.id = m.cell_id
        where m.church_id = ?` + (onlyMyCell ? ' and m.cell_id = ?' : '') +
      ` order by case m.status when 'pending' then 0 when 'active' then 1 else 2 end, m.name`;
    const st = onlyMyCell
      ? env.DB.prepare(sql).bind(me.church_id, me.cell_id)
      : env.DB.prepare(sql).bind(me.church_id);
    const r = await st.all();
    return json({ members: r.results, canEdit: canManage(me), canGrantRole: isOwner(me) });
  }

  if (P('/members/update') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const t = await env.DB.prepare('select * from members where id = ? and church_id = ?').bind(b.memberId, me.church_id).first();
    if (!t) return fail('성도를 찾을 수 없습니다');
    if (t.role === 'owner' && t.id !== me.id) return fail('담임목사 계정은 바꿀 수 없습니다', 403);

    const sets = [], args = [], logs = [];
    if (b.title !== undefined && b.title !== t.title) {
      if (!TITLES.includes(b.title)) return fail('알 수 없는 직분입니다');
      sets.push('title = ?'); args.push(b.title); logs.push(`직분 ${t.title} → ${b.title}`);
    }
    if (b.cellId !== undefined && b.cellId !== t.cell_id) {
      if (b.cellId) {
        const c = await env.DB.prepare('select name from cells where id = ? and church_id = ?').bind(b.cellId, me.church_id).first();
        if (!c) return fail('셀을 찾을 수 없습니다');
        logs.push(`셀 → ${c.name}`);
      } else logs.push('셀에서 뺌');
      sets.push('cell_id = ?'); args.push(b.cellId || null);
    }
    if (b.role !== undefined && b.role !== t.role) {
      if (!isOwner(me)) return fail('권한 부여는 목사님만 할 수 있습니다', 403);
      if (!ROLES.includes(b.role) || b.role === 'owner') return fail('줄 수 없는 권한입니다');
      sets.push('role = ?'); args.push(b.role); logs.push(`권한 ${t.role} → ${b.role}`);
    }
    if (b.shareScope !== undefined && b.shareScope !== t.share_scope) {
      if (!isOwner(me)) return fail('나눔 범위는 목사님만 바꿀 수 있습니다', 403);
      if (!SCOPES.includes(b.shareScope)) return fail('알 수 없는 나눔 범위입니다');
      sets.push('share_scope = ?'); args.push(b.shareScope);
      logs.push(`나눔 범위 → ${b.shareScope === 'all_cells' ? '셀 선택 가능' : '본인 셀만'}`);
    }
    if (b.status !== undefined && b.status !== t.status) {
      if (!['active', 'inactive'].includes(b.status)) return fail('알 수 없는 상태입니다');
      sets.push('status = ?'); args.push(b.status); logs.push(`상태 → ${b.status === 'active' ? '사용' : '중지'}`);
      if (b.status === 'inactive') await env.DB.prepare('delete from sessions where member_id = ?').bind(t.id).run();
    }
    if (!sets.length) return json({ ok: true });
    args.push(t.id);
    await env.DB.prepare(`update members set ${sets.join(', ')} where id = ?`).bind(...args).run();
    await audit(env, me, '성도 정보 변경', t, logs.join(', '));
    return json({ ok: true });
  }

  if (P('/members/approve') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const t = await env.DB.prepare(`select * from members where id = ? and church_id = ? and status='pending'`)
      .bind(b.memberId, me.church_id).first();
    if (!t) return fail('신청을 찾을 수 없습니다');
    if (b.approve) {
      await env.DB.prepare(`update members set status='active', cell_id = ? where id = ?`).bind(b.cellId || null, t.id).run();
      await audit(env, me, '가입 승인', t, null);
    } else {
      await env.DB.prepare('delete from members where id = ?').bind(t.id).run();
      await audit(env, me, '가입 거절', t, null);
    }
    return json({ ok: true });
  }

  if (P('/members/reset-pin') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const t = await env.DB.prepare('select * from members where id = ? and church_id = ?').bind(b.memberId, me.church_id).first();
    if (!t) return fail('성도를 찾을 수 없습니다');
    if (t.role === 'owner' && t.id !== me.id) return fail('담임목사 계정은 바꿀 수 없습니다', 403);
    const temp = String(Math.floor(100000 + Math.random() * 900000));
    const salt = randomHex(16);
    await env.DB.prepare(
      'update members set pin_hash = ?, pin_salt = ?, must_change_pin = 1, fail_count = 0, locked_until = null where id = ?'
    ).bind(await derive(temp, salt), salt, t.id).run();
    await env.DB.prepare('delete from sessions where member_id = ?').bind(t.id).run();
    await audit(env, me, '비밀번호 초기화', t, null);
    return json({ ok: true, tempPin: temp });
  }

  if (P('/audit')) {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const r = await env.DB.prepare(
      `select id, actor_name, action, target_name, detail, created_at
         from audit_log where church_id = ? order by created_at desc limit 200`
    ).bind(me.church_id).all();
    return json({ logs: r.results });
  }

  // ── 교회 설정 (줌 링크) ────────────────────────────────────────────────
  if (P('/settings/zoom') && req.method === 'POST') {
    if (!isOwner(me)) return fail('줌 링크는 목사님만 설정할 수 있습니다', 403);
    const b = await body(req);
    const v = (b.zoomUrl || '').trim();
    if (v && !/^https?:\/\/[^\s]+zoom\.us\//i.test(v)) return fail('줌 주소가 아닌 것 같습니다. https://...zoom.us/j/... 형태여야 합니다');
    await env.DB.prepare('update churches set zoom_url = ? where id = ?').bind(v || null, me.church_id).run();
    await audit(env, me, '줌 링크 변경', null, v ? '설정됨' : '지움');
    return json({ ok: true });
  }

  if (P('/settings/invite')) {
    if (!isOwner(me)) return fail('권한이 없습니다', 403);
    let ch = await env.DB.prepare('select invite_code from churches where id = ?').bind(me.church_id).first();
    if (!ch.invite_code) {
      const code = randomHex(4).toUpperCase();
      await env.DB.prepare('update churches set invite_code = ? where id = ?').bind(code, me.church_id).run();
      ch = { invite_code: code };
    }
    return json({ inviteCode: ch.invite_code });
  }

  // ══════════════════════════════════════════════════════════════════════
  //  묵상
  // ══════════════════════════════════════════════════════════════════════

  // 내가 쓴 글 (달력·완료율용)
  if (P('/entries/mine')) {
    const from = q('from') || today().slice(0, 4) + '-01-01';
    const to = q('to') || today();
    const r = await env.DB.prepare(
      `select id, date, title, body, is_public, created_at, updated_at
         from entries where member_id = ? and date between ? and ? order by date desc`
    ).bind(me.id, from, to).all();
    return json({ entries: r.results });
  }

  if (P('/entries/save') && req.method === 'POST') {
    const b = await body(req);
    const date = (b.date || today()).slice(0, 10);
    if (!b.body || !b.body.trim()) return fail('묵상 내용을 적어 주세요');

    // 나눔 대상 셀 결정
    let targets = [];
    if (canShareAnywhere(me)) {
      if (b.allCells) {
        const all = await env.DB.prepare('select id from cells where church_id = ?').bind(me.church_id).all();
        targets = all.results.map((x) => x.id);
      } else if (Array.isArray(b.cellIds) && b.cellIds.length) {
        const ok = await env.DB.prepare(
          `select id from cells where church_id = ? and id in (${b.cellIds.map(() => '?').join(',')})`
        ).bind(me.church_id, ...b.cellIds).all();
        targets = ok.results.map((x) => x.id);
      } else if (me.cell_id) targets = [me.cell_id];
    } else {
      // 일반 성도는 본인 셀에만
      if (!me.cell_id) return fail('아직 셀이 정해지지 않았습니다. 목사님께 문의해 주세요.');
      targets = [me.cell_id];
    }

    const exist = await env.DB.prepare('select id from entries where member_id = ? and date = ?').bind(me.id, date).first();
    let entryId;
    if (exist) {
      await env.DB.prepare('update entries set title = ?, body = ?, is_public = ?, updated_at = ? where id = ?')
        .bind(b.title || null, b.body.trim(), b.isPublic === false ? 0 : 1, nowIso(), exist.id).run();
      entryId = exist.id;
      await env.DB.prepare('delete from entry_targets where entry_id = ?').bind(entryId).run();
    } else {
      const row = await env.DB.prepare(
        `insert into entries(church_id, member_id, date, title, body, is_public) values(?,?,?,?,?,?) returning id`
      ).bind(me.church_id, me.id, date, b.title || null, b.body.trim(), b.isPublic === false ? 0 : 1).first();
      entryId = row.id;
    }
    for (const cid of targets) {
      await env.DB.prepare('insert or ignore into entry_targets(entry_id, cell_id) values(?,?)').bind(entryId, cid).run();
    }
    return json({ ok: true, id: entryId, cellCount: targets.length });
  }

  if (P('/entries/delete') && req.method === 'POST') {
    const b = await body(req);
    const e = await env.DB.prepare('select * from entries where id = ?').bind(b.id).first();
    if (!e) return fail('글을 찾을 수 없습니다');
    if (e.member_id !== me.id) return fail('본인 글만 지울 수 있습니다', 403);
    await env.DB.prepare('delete from entries where id = ?').bind(b.id).run();
    return json({ ok: true });
  }

  // 오늘의 나눔 (scope=cell 우리 셀 / all 전체)
  if (P('/entries/feed')) {
    const date = q('date') || today();
    const scope = q('scope') === 'all' ? 'all' : 'cell';
    const cells = await visibleCellIds(env, me, scope);
    const rows = await feedQuery(env, me, { date, scope, cells });
    return json({ entries: rows, scope });
  }

  // 출석표 (분기 단위)
  if (P('/entries/attendance')) {
    const scope = q('scope') === 'all' ? 'all' : 'cell';
    const year = parseInt(q('year') || today().slice(0, 4), 10);
    const quarter = parseInt(q('quarter') || String(Math.floor(new Date().getMonth() / 3) + 1), 10);
    const startMonth = (quarter - 1) * 3 + 1;
    const from = `${year}-${String(startMonth).padStart(2, '0')}-01`;
    const endMonth = startMonth + 2;
    const lastDay = new Date(year, endMonth, 0).getDate();
    const to = `${year}-${String(endMonth).padStart(2, '0')}-${lastDay}`;

    const cells = await visibleCellIds(env, me, scope);
    const where = scope === 'all' ? 'm.church_id = ?' : (cells.length ? 'm.cell_id in (' + cells.map(() => '?').join(',') + ')' : '0');
    const args = scope === 'all' ? [me.church_id] : cells;
    if (where === '0') return json({ from, to, people: [], year, quarter });

    const people = await env.DB.prepare(
      `select m.id, m.name, m.title, c.name as cell_name from members m
         left join cells c on c.id = m.cell_id
        where ${where} and m.status = 'active' order by c.name, m.name`
    ).bind(...args).all();
    const marks = await env.DB.prepare(
      `select e.member_id, e.date from entries e
         join members m on m.id = e.member_id
        where ${where} and e.date between ? and ?`
    ).bind(...args, from, to).all();
    const byMember = {};
    for (const row of marks.results) (byMember[row.member_id] ||= []).push(row.date);
    return json({
      from, to, year, quarter,
      people: people.results.map((p) => ({ ...p, dates: byMember[p.id] || [] })),
    });
  }

  // 한 사람의 묵상기록
  if (P('/entries/person')) {
    const memberId = parseInt(q('memberId') || me.id, 10);
    const t = await env.DB.prepare('select id, name, title, cell_id from members where id = ? and church_id = ?')
      .bind(memberId, me.church_id).first();
    if (!t) return fail('성도를 찾을 수 없습니다');
    const mine = memberId === me.id;
    const month = q('month'), day = q('day');
    let sql = `select e.id, e.date, e.title, e.body, e.is_public,
                      (select count(*) from likes l where l.entry_id = e.id) as like_count,
                      (select count(*) from likes l where l.entry_id = e.id and l.member_id = ?) as liked,
                      (select count(*) from comments c where c.entry_id = e.id) as comment_count
                 from entries e where e.member_id = ?`;
    const args = [me.id, memberId];
    if (!mine) sql += ' and e.is_public = 1';
    if (month && month !== 'all') { sql += ` and substr(e.date,6,2) = ?`; args.push(String(month).padStart(2, '0')); }
    if (day && day !== 'all') { sql += ` and substr(e.date,9,2) = ?`; args.push(String(day).padStart(2, '0')); }
    sql += ' order by e.date desc limit 300';
    const r = await env.DB.prepare(sql).bind(...args).all();
    const total = await env.DB.prepare(
      `select count(*) as c from entries where member_id = ?` + (mine ? '' : ' and is_public = 1')
    ).bind(memberId).first();
    return json({ person: t, total: total.c, entries: r.results });
  }

  // 묵상 검색
  if (P('/entries/search')) {
    const term = (q('q') || '').trim();
    const memberId = q('memberId');
    const from = q('from'), to = q('to');
    if (!term && !memberId) return json({ entries: [] });
    const scope = q('scope') === 'all' ? 'all' : 'cell';
    const cells = await visibleCellIds(env, me, scope);
    let sql = `select e.id, e.date, e.title, e.body, m.id as member_id, m.name as member_name, m.title as member_title
                 from entries e join members m on m.id = e.member_id
                where e.church_id = ? and (e.is_public = 1 or e.member_id = ?)`;
    const args = [me.church_id, me.id];
    if (scope !== 'all') {
      if (!cells.length) return json({ entries: [] });
      sql += ` and (m.cell_id in (${cells.map(() => '?').join(',')}) or e.member_id = ?)`;
      args.push(...cells, me.id);
    }
    if (term) { sql += ' and (e.title like ? or e.body like ?)'; args.push('%' + term + '%', '%' + term + '%'); }
    if (memberId) { sql += ' and e.member_id = ?'; args.push(parseInt(memberId, 10)); }
    if (from) { sql += ' and e.date >= ?'; args.push(from); }
    if (to) { sql += ' and e.date <= ?'; args.push(to); }
    sql += ' order by e.date desc limit 100';
    const r = await env.DB.prepare(sql).bind(...args).all();
    return json({ entries: r.results });
  }

  // 나의 묵상 완료율
  if (P('/entries/progress')) {
    const year = parseInt(q('year') || today().slice(0, 4), 10);
    const rows = await env.DB.prepare(
      `select date from entries where member_id = ? and substr(date,1,4) = ?`
    ).bind(me.id, String(year)).all();
    const dates = rows.results.map((r) => r.date);
    const now = new Date();
    const quarters = [];
    for (let qi = 1; qi <= 4; qi++) {
      const sm = (qi - 1) * 3 + 1, em = sm + 2;
      const start = new Date(year, sm - 1, 1);
      const end = new Date(year, em, 0);
      const cap = now < end ? now : end;
      const days = start > cap ? 0 : Math.floor((cap - start) / 86400000) + 1;
      const done = dates.filter((d) => {
        const m = parseInt(d.slice(5, 7), 10);
        return m >= sm && m <= em;
      }).length;
      quarters.push({ quarter: qi, done, total: days, percent: days ? Math.round((done / days) * 100) : 0 });
    }
    const yStart = new Date(year, 0, 1);
    const yEnd = new Date(year, 11, 31);
    const yCap = now < yEnd ? now : yEnd;
    const yDays = Math.floor((yCap - yStart) / 86400000) + 1;
    return json({
      year, quarters,
      annual: { done: dates.length, total: yDays, percent: yDays ? Math.round((dates.length / yDays) * 100) : 0 },
    });
  }

  if (P('/entries/like') && req.method === 'POST') {
    const b = await body(req);
    const e = await env.DB.prepare('select id from entries where id = ? and church_id = ?').bind(b.entryId, me.church_id).first();
    if (!e) return fail('글을 찾을 수 없습니다');
    const had = await env.DB.prepare('select 1 as x from likes where entry_id = ? and member_id = ?').bind(b.entryId, me.id).first();
    if (had) await env.DB.prepare('delete from likes where entry_id = ? and member_id = ?').bind(b.entryId, me.id).run();
    else await env.DB.prepare('insert into likes(entry_id, member_id) values(?,?)').bind(b.entryId, me.id).run();
    const c = await env.DB.prepare('select count(*) as c from likes where entry_id = ?').bind(b.entryId).first();
    return json({ liked: !had, count: c.c });
  }

  if (P('/entries/comments')) {
    const entryId = parseInt(q('entryId'), 10);
    const r = await env.DB.prepare(
      `select c.id, c.body, c.created_at, m.id as member_id, m.name as member_name, m.title as member_title
         from comments c join members m on m.id = c.member_id
        where c.entry_id = ? order by c.created_at`
    ).bind(entryId).all();
    return json({ comments: r.results, myId: me.id });
  }

  if (P('/entries/comment') && req.method === 'POST') {
    const b = await body(req);
    if (!b.body || !b.body.trim()) return fail('댓글을 적어 주세요');
    const e = await env.DB.prepare('select id from entries where id = ? and church_id = ?').bind(b.entryId, me.church_id).first();
    if (!e) return fail('글을 찾을 수 없습니다');
    await env.DB.prepare('insert into comments(entry_id, member_id, body) values(?,?,?)')
      .bind(b.entryId, me.id, b.body.trim()).run();
    return json({ ok: true });
  }

  if (P('/entries/comment/delete') && req.method === 'POST') {
    const b = await body(req);
    const c = await env.DB.prepare('select * from comments where id = ?').bind(b.id).first();
    if (!c) return fail('댓글을 찾을 수 없습니다');
    if (c.member_id !== me.id && !canManage(me)) return fail('본인 댓글만 지울 수 있습니다', 403);
    await env.DB.prepare('delete from comments where id = ?').bind(b.id).run();
    return json({ ok: true });
  }

  // 셀장용 우리 셀 현황판
  if (P('/cells/status')) {
    const cellId = parseInt(q('cellId') || me.cell_id || 0, 10);
    if (!cellId) return fail('셀이 없습니다');
    if (!canManage(me) && me.cell_id !== cellId) return fail('권한이 없습니다', 403);
    const date = q('date') || today();
    const r = await env.DB.prepare(
      `select m.id, m.name, m.title,
              (select count(*) from entries e where e.member_id = m.id and e.date = ?) as wrote_today,
              (select count(*) from entries e where e.member_id = m.id and e.date >= date(?, '-6 day') and e.date <= ?) as week_count,
              (select max(e.date) from entries e where e.member_id = m.id) as last_date
         from members m where m.cell_id = ? and m.status = 'active' order by m.name`
    ).bind(date, date, date, cellId).all();
    const cell = await env.DB.prepare('select name from cells where id = ?').bind(cellId).first();
    return json({ cellName: cell?.name, date, people: r.results });
  }

  // ══════════════════════════════════════════════════════════════════════
  //  기도제목
  // ══════════════════════════════════════════════════════════════════════
  if (P('/prayers')) {
    const scope = q('scope') === 'all' ? 'all' : 'cell';
    const cells = await visibleCellIds(env, me, scope);
    let sql = `select p.id, p.body, p.answered, p.created_at,
                      m.id as member_id, m.name as member_name, m.title as member_title,
                      (select count(*) from prayer_amens a where a.prayer_id = p.id) as amen_count,
                      (select count(*) from prayer_amens a where a.prayer_id = p.id and a.member_id = ?) as amened
                 from prayers p join members m on m.id = p.member_id
                where p.church_id = ?`;
    const args = [me.id, me.church_id];
    if (scope !== 'all') {
      if (!cells.length) return json({ prayers: [] });
      sql += ` and (exists (select 1 from prayer_targets t where t.prayer_id = p.id and t.cell_id in (${cells.map(() => '?').join(',')}))
                    or p.member_id = ?)`;
      args.push(...cells, me.id);
    }
    sql += ' order by p.answered, p.created_at desc limit 200';
    const r = await env.DB.prepare(sql).bind(...args).all();
    return json({ prayers: r.results, scope });
  }

  if (P('/prayers/save') && req.method === 'POST') {
    const b = await body(req);
    if (!b.body || !b.body.trim()) return fail('기도제목을 적어 주세요');
    let targets = [];
    if (canShareAnywhere(me) && (b.allCells || (Array.isArray(b.cellIds) && b.cellIds.length))) {
      if (b.allCells) {
        const all = await env.DB.prepare('select id from cells where church_id = ?').bind(me.church_id).all();
        targets = all.results.map((x) => x.id);
      } else {
        const ok = await env.DB.prepare(
          `select id from cells where church_id = ? and id in (${b.cellIds.map(() => '?').join(',')})`
        ).bind(me.church_id, ...b.cellIds).all();
        targets = ok.results.map((x) => x.id);
      }
    } else if (me.cell_id) targets = [me.cell_id];
    else return fail('아직 셀이 정해지지 않았습니다. 목사님께 문의해 주세요.');

    const row = await env.DB.prepare('insert into prayers(church_id, member_id, body) values(?,?,?) returning id')
      .bind(me.church_id, me.id, b.body.trim()).first();
    for (const cid of targets)
      await env.DB.prepare('insert or ignore into prayer_targets(prayer_id, cell_id) values(?,?)').bind(row.id, cid).run();
    return json({ ok: true, id: row.id });
  }

  if (P('/prayers/amen') && req.method === 'POST') {
    const b = await body(req);
    const had = await env.DB.prepare('select 1 as x from prayer_amens where prayer_id = ? and member_id = ?')
      .bind(b.id, me.id).first();
    if (had) await env.DB.prepare('delete from prayer_amens where prayer_id = ? and member_id = ?').bind(b.id, me.id).run();
    else await env.DB.prepare('insert into prayer_amens(prayer_id, member_id) values(?,?)').bind(b.id, me.id).run();
    const c = await env.DB.prepare('select count(*) as c from prayer_amens where prayer_id = ?').bind(b.id).first();
    return json({ amened: !had, count: c.c });
  }

  if (P('/prayers/answered') && req.method === 'POST') {
    const b = await body(req);
    const p = await env.DB.prepare('select * from prayers where id = ?').bind(b.id).first();
    if (!p) return fail('기도제목을 찾을 수 없습니다');
    if (p.member_id !== me.id) return fail('본인 기도제목만 바꿀 수 있습니다', 403);
    await env.DB.prepare('update prayers set answered = ? where id = ?').bind(b.answered ? 1 : 0, b.id).run();
    return json({ ok: true });
  }

  if (P('/prayers/delete') && req.method === 'POST') {
    const b = await body(req);
    const p = await env.DB.prepare('select * from prayers where id = ?').bind(b.id).first();
    if (!p) return fail('기도제목을 찾을 수 없습니다');
    if (p.member_id !== me.id && !canManage(me)) return fail('본인 기도제목만 지울 수 있습니다', 403);
    await env.DB.prepare('delete from prayers where id = ?').bind(b.id).run();
    return json({ ok: true });
  }

  // ══════════════════════════════════════════════════════════════════════
  //  공지사항
  // ══════════════════════════════════════════════════════════════════════
  if (P('/notices')) {
    const r = await env.DB.prepare(
      `select n.id, n.title, n.body, n.pinned, n.created_at, m.name as author_name,
              (select count(*) from notice_reads x where x.notice_id = n.id and x.member_id = ?) as read_flag,
              (select count(*) from notice_reads x where x.notice_id = n.id) as read_count
         from notices n left join members m on m.id = n.author_id
        where n.church_id = ? order by n.pinned desc, n.created_at desc limit 100`
    ).bind(me.id, me.church_id).all();
    return json({ notices: r.results, canEdit: canManage(me) });
  }

  if (P('/notices/save') && req.method === 'POST') {
    if (!canManage(me)) return fail('공지는 목사님(또는 관리 권한자)만 올릴 수 있습니다', 403);
    const b = await body(req);
    if (!b.body || !b.body.trim()) return fail('공지 내용을 적어 주세요');
    if (b.id) {
      await env.DB.prepare('update notices set title = ?, body = ?, pinned = ? where id = ? and church_id = ?')
        .bind(b.title || null, b.body.trim(), b.pinned ? 1 : 0, b.id, me.church_id).run();
      await env.DB.prepare('delete from notice_reads where notice_id = ?').bind(b.id).run();
    } else {
      await env.DB.prepare('insert into notices(church_id, author_id, title, body, pinned) values(?,?,?,?,?)')
        .bind(me.church_id, me.id, b.title || null, b.body.trim(), b.pinned ? 1 : 0).run();
    }
    return json({ ok: true });
  }

  if (P('/notices/read') && req.method === 'POST') {
    const b = await body(req);
    await env.DB.prepare('insert or ignore into notice_reads(notice_id, member_id) values(?,?)').bind(b.id, me.id).run();
    return json({ ok: true });
  }

  if (P('/notices/delete') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    await env.DB.prepare('delete from notices where id = ? and church_id = ?').bind(b.id, me.church_id).run();
    return json({ ok: true });
  }

  // ══════════════════════════════════════════════════════════════════════
  //  주보 (R2)
  // ══════════════════════════════════════════════════════════════════════
  if (P('/bulletins')) {
    const r = await env.DB.prepare(
      `select b.id, b.title, b.week_of, b.mime, b.size, b.created_at, m.name as uploader
         from bulletins b left join members m on m.id = b.uploaded_by
        where b.church_id = ? order by b.week_of desc, b.id desc limit 200`
    ).bind(me.church_id).all();
    return json({ bulletins: r.results, canEdit: canManage(me) });
  }

  // 파일 본문을 그대로 POST. 헤더: Content-Type, X-File-Name
  if (P('/bulletins/upload') && req.method === 'POST') {
    if (!canManage(me)) return fail('주보는 목사님(또는 관리 권한자)만 올릴 수 있습니다', 403);
    const max = parseInt(env.MAX_UPLOAD_BYTES || '10485760', 10);
    const mime = req.headers.get('Content-Type') || 'application/octet-stream';
    const okMime = ['image/png', 'image/jpeg', 'image/webp', 'application/pdf'];
    if (!okMime.includes(mime.split(';')[0].trim()))
      return fail('png, jpg, webp, pdf 파일만 올릴 수 있습니다');
    const buf = await req.arrayBuffer();
    if (buf.byteLength === 0) return fail('파일이 비어 있습니다');
    if (buf.byteLength > max)
      return fail(`파일이 너무 큽니다. ${Math.round(max / 1024 / 1024)}MB 이하로 줄여 주세요. (지금 ${(buf.byteLength / 1024 / 1024).toFixed(1)}MB)`);

    const weekOf = (q('weekOf') || today()).slice(0, 10);
    const title = q('title') || req.headers.get('X-File-Name') || (weekOf + ' 주보');
    const ext = mime.includes('pdf') ? 'pdf' : mime.includes('png') ? 'png' : mime.includes('webp') ? 'webp' : 'jpg';
    const key = `church-${me.church_id}/${weekOf}-${randomHex(6)}.${ext}`;
    await env.FILES.put(key, buf, { httpMetadata: { contentType: mime } });
    const row = await env.DB.prepare(
      `insert into bulletins(church_id, title, week_of, r2_key, mime, size, uploaded_by)
       values(?,?,?,?,?,?,?) returning id`
    ).bind(me.church_id, decodeURIComponent(title), weekOf, key, mime, buf.byteLength, me.id).first();
    await audit(env, me, '주보 올림', null, weekOf);
    return json({ ok: true, id: row.id });
  }

  // /api/bulletins/{id}/file
  const bf = path.match(/^\/api\/bulletins\/(\d+)\/file$/);
  if (bf) {
    const b = await env.DB.prepare('select * from bulletins where id = ? and church_id = ?')
      .bind(parseInt(bf[1], 10), me.church_id).first();
    if (!b) return fail('주보를 찾을 수 없습니다', 404);
    const obj = await env.FILES.get(b.r2_key);
    if (!obj) return fail('파일이 없습니다', 404);
    return new Response(obj.body, {
      headers: {
        'Content-Type': b.mime,
        'Content-Length': String(b.size),
        'Cache-Control': 'private, max-age=3600',
        ...CORS,
      },
    });
  }

  if (P('/bulletins/delete') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const row = await env.DB.prepare('select * from bulletins where id = ? and church_id = ?').bind(b.id, me.church_id).first();
    if (!row) return fail('주보를 찾을 수 없습니다');
    await env.FILES.delete(row.r2_key);
    await env.DB.prepare('delete from bulletins where id = ?').bind(b.id).run();
    await audit(env, me, '주보 삭제', null, row.week_of);
    return json({ ok: true });
  }

  // ══════════════════════════════════════════════════════════════════════
  //  건의함
  // ══════════════════════════════════════════════════════════════════════
  if (P('/suggestions') && req.method === 'POST') {
    const b = await body(req);
    if (!b.body || !b.body.trim()) return fail('내용을 적어 주세요');
    await env.DB.prepare('insert into suggestions(church_id, member_id, name, body) values(?,?,?,?)')
      .bind(me.church_id, me.id, me.name, b.body.trim()).run();
    return json({ ok: true });
  }
  if (P('/suggestions')) {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const r = await env.DB.prepare(
      'select id, name, body, is_read, created_at from suggestions where church_id = ? order by created_at desc limit 200'
    ).bind(me.church_id).all();
    return json({ suggestions: r.results });
  }

  // ══════════════════════════════════════════════════════════════════════
  //  플랫폼 운영자 — 교회 등록 신청 심사
  // ══════════════════════════════════════════════════════════════════════
  if (path.startsWith('/api/platform/')) {
    if (!me.is_platform_admin) return fail('권한이 없습니다', 403);

    if (P('/platform/churches')) {
      const status = q('status') || 'pending';
      const r = await env.DB.prepare(
        `select c.*, (select count(*) from members m where m.church_id = c.id) as member_count,
                (select name from churches p where p.id = c.invited_by) as invited_by_name
           from churches c where c.status = ? order by c.created_at desc limit 200`
      ).bind(status).all();
      return json({ churches: r.results });
    }

    if (P('/platform/churches/review') && req.method === 'POST') {
      const b = await body(req);
      if (!['approved', 'rejected', 'suspended'].includes(b.status)) return fail('알 수 없는 상태입니다');
      await env.DB.prepare('update churches set status = ?, reject_reason = ?, reviewed_at = ? where id = ?')
        .bind(b.status, b.reason || null, nowIso(), b.churchId).run();
      if (b.status !== 'approved')
        await env.DB.prepare('delete from sessions where member_id in (select id from members where church_id = ?)')
          .bind(b.churchId).run();
      return json({ ok: true });
    }
  }

  return fail('없는 주소입니다: ' + path, 404);
}

// ── 나눔 목록 공통 질의 ──────────────────────────────────────────────────
async function feedQuery(env, me, { date, scope, cells }) {
  let sql = `select e.id, e.date, e.title, e.body, e.created_at,
                    m.id as member_id, m.name as member_name, m.title as member_title,
                    c.name as cell_name,
                    (select count(*) from likes l where l.entry_id = e.id) as like_count,
                    (select count(*) from likes l where l.entry_id = e.id and l.member_id = ?) as liked,
                    (select count(*) from comments x where x.entry_id = e.id) as comment_count
               from entries e
               join members m on m.id = e.member_id
               left join cells c on c.id = m.cell_id
              where e.church_id = ? and e.date = ? and (e.is_public = 1 or e.member_id = ?)`;
  const args = [me.id, me.church_id, date, me.id];
  if (scope !== 'all') {
    if (!cells.length) return [];
    // 이 글이 내 셀로 나눠졌거나, 글쓴이가 내 셀 사람이거나, 내 글
    sql += ` and ( exists (select 1 from entry_targets t where t.entry_id = e.id and t.cell_id in (${cells.map(() => '?').join(',')}))
                   or m.cell_id in (${cells.map(() => '?').join(',')})
                   or e.member_id = ? )`;
    args.push(...cells, ...cells, me.id);
  }
  sql += ' order by e.created_at desc limit 200';
  const r = await env.DB.prepare(sql).bind(...args).all();
  return r.results;
}
