// ══════════════════════════════════════════════════════════════════════════
//  모두의 교회 · Cloudflare Workers API
//  D1(데이터) + R2(주보 파일). 안드로이드 앱이 이 API 를 호출합니다.
// ══════════════════════════════════════════════════════════════════════════

import { privacyPage } from './privacy.js';
import { deleteAccountPage } from './delete-account.js';
import { childSafetyPage } from './child-safety.js';
import { sharePage } from './share.js';
import { pushTo, membersInCells, membersInChurch, churchAdmins, platformAdmins } from './push.js';

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
// owner = 관리자 (교회를 등록한 분 · 담임목사님과 같은 전체 권한. 여러 명 가능)
// admin = 부관리자 (성도·셀 관리, 가입 승인, 공지·주보)
const ROLES = ['owner', 'admin', 'leader', 'member'];
const ROLE_LABEL = { owner: '관리자', admin: '부관리자', leader: '셀장', member: '성도' };
const MAX_IMAGES = 4;                 // 묵상 한 편에 붙일 수 있는 사진 수
const MAX_IMAGE_BYTES = 3 * 1024 * 1024;

// ── 여러 셀(구역)에 속한 분의 셀 이름을 짧게 ──────────────────────────────────
//   모든 셀  → '전체 구역'   (교회가 쓰는 말이 '셀'이면 '전체 셀')
//   두 개 이상 → '1구역 외 2'
//   한 개     → 그 이름
// 긴 이름 목록 때문에 화면이 깨지지 않게 서버에서 한 번에 줄여 보냅니다.
function cellWord(names) {
  if (names.some((n) => /구역$/.test(n))) return '구역';
  if (names.some((n) => /셀$/.test(n))) return '셀';
  if (names.some((n) => /목장$/.test(n))) return '목장';
  return '셀';
}
function shortCellLabel(full, allNames) {
  if (!full) return null;
  const mine = String(full).split(', ').filter(Boolean);
  if (mine.length <= 1) return mine[0] || null;
  if (allNames.length > 1 && mine.length >= allNames.length) return '전체 ' + cellWord(allNames);
  return `${mine[0]} 외 ${mine.length - 1}`;
}
async function churchCellNames(env, churchId) {
  const r = await env.DB.prepare('select name from cells where church_id = ? order by name').bind(churchId).all();
  return r.results.map((x) => x.name);
}
async function shortenCells(env, churchId, rows) {
  if (!rows.some((r) => r.cell_name && String(r.cell_name).includes(', '))) return rows;
  const all = await churchCellNames(env, churchId);
  rows.forEach((r) => { if (r.cell_name) { r.cell_names = r.cell_name; r.cell_name = shortCellLabel(r.cell_name, all); } });
  return rows;
}

async function ownerCount(env, churchId) {
  const r = await env.DB.prepare(
    `select count(*) as c from members where church_id = ? and role = 'owner' and status = 'active'`
  ).bind(churchId).first();
  return r?.c ?? 0;
}
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

// 한 사람이 속한 셀들 (여러 개일 수 있습니다)
async function myCellIds(env, memberId, fallback = null) {
  const r = await env.DB.prepare('select cell_id from member_cells where member_id = ?')
    .bind(memberId).all();
  const ids = r.results.map((x) => x.cell_id);
  if (ids.length) return ids;
  return fallback ? [fallback] : [];
}

// 내가 볼 수 있는 셀 목록 (내가 속한 셀들, 또는 교회 전체)
async function visibleCellIds(env, me, scope) {
  if (scope === 'all') {
    const r = await env.DB.prepare('select id from cells where church_id = ?').bind(me.church_id).all();
    return r.results.map((x) => x.id);
  }
  return myCellIds(env, me.id, me.cell_id);
}

// 소속 셀을 통째로 다시 지정합니다. 대표 셀(cell_id)도 함께 맞춥니다.
async function setMemberCells(env, memberId, cellIds) {
  await env.DB.prepare('delete from member_cells where member_id = ?').bind(memberId).run();
  for (const cid of cellIds)
    await env.DB.prepare('insert or ignore into member_cells(member_id, cell_id) values(?,?)')
      .bind(memberId, cid).run();
  await env.DB.prepare('update members set cell_id = ? where id = ?')
    .bind(cellIds[0] ?? null, memberId).run();
}

// '이 사람이 이 셀들 중 하나에 속해 있는가' 조건문
const inCells = (ids) =>
  `exists (select 1 from member_cells mc where mc.member_id = m.id and mc.cell_id in (${ids.map(() => '?').join(',')}))`;

// ══════════════════════════════════════════════════════════════════════════
export default {
  async fetch(req, env, ctx) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    try {
      return await route(req, env, url, path, ctx);
    } catch (e) {
      return fail('서버 오류: ' + (e?.message || e), 500);
    }
  },
};

async function body(req) {
  try { return await req.json(); } catch { return {}; }
}

async function route(req, env, url, path, ctx) {
  const P = (s) => path === '/api' + s;
  const q = (k) => url.searchParams.get(k);

  if (path === '/' || path === '/api') return json({ ok: true, name: '모두의 교회 API' });

  // 아동 안전 기준(CSAE) — Play 스토어가 요구하는 공개 페이지
  if (path === '/child-safety') {
    return new Response(childSafetyPage(env.CONTACT_EMAIL || 'woori.church.app@example.com'), {
      headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'public, max-age=3600' },
    });
  }

  // 계정 삭제 요청 — 로그인 없이 누구나 (Play 스토어 요구사항)
  if (path === '/delete-account') {
    const html = (sent) => new Response(
      deleteAccountPage(env.CONTACT_EMAIL || 'woori.church.app@example.com', sent),
      { headers: { 'Content-Type': 'text/html; charset=utf-8' } }
    );
    if (req.method !== 'POST') return html(false);

    const form = await req.formData().catch(() => null);
    const g = (k, max) => String(form?.get(k) ?? '').trim().slice(0, max);
    const name = g('name', 40);
    if (!name) return html(false);
    await env.DB.prepare(
      'insert into deletion_requests(church_name, member_name, contact, note) values(?,?,?,?)'
    ).bind(g('church', 60) || null, name, g('contact', 100) || null, g('note', 500) || null).run();

    try {
      ctx?.waitUntil((async () => {
        const admins = await platformAdmins(env);
        await pushTo(env, admins, {
          title: '계정 삭제 요청',
          body: '삭제 요청이 들어왔습니다. 확인해 주세요.',
          link: 'platform', kind: 'admin',
        });
      })());
    } catch { /* 알림 실패는 무시 */ }
    return html(true);
  }

  // 묵상 사진 — /img/<token>. token 은 추측할 수 없는 128비트 난수라
  // 앱의 사진 표시기(Coil)와 웹의 <img> 가 로그인 헤더 없이 바로 불러올 수 있습니다.
  const im = path.match(/^\/img\/([0-9a-f]{32})$/);
  if (im) {
    const row = await env.DB.prepare('select r2_key, mime from entry_images where token = ?').bind(im[1]).first();
    if (!row) return new Response('없는 사진입니다', { status: 404, headers: CORS });
    const obj = await env.FILES.get(row.r2_key);
    if (!obj) return new Response('없는 사진입니다', { status: 404, headers: CORS });
    return new Response(obj.body, {
      headers: {
        'Content-Type': row.mime,
        // 같은 주소의 사진은 절대 바뀌지 않으므로 오래 저장해 둬도 됩니다
        'Cache-Control': 'private, max-age=31536000, immutable',
        ...CORS,
      },
    });
  }

  // 공유된 묵상 한 편 — /s/<token>. 로그인 없이 그 글 하나만 보여 줍니다.
  const sh = path.match(/^\/s\/([0-9a-f]{24})$/);
  if (sh) {
    const row = await env.DB.prepare(
      `select e.id, e.date, e.title, e.body, m.name as member_name, m.title as member_title, c.name as church_name
         from entry_shares s
         join entries e on e.id = s.entry_id
         join members m on m.id = e.member_id
         join churches c on c.id = e.church_id
        where s.token = ? and e.is_public = 1 and c.status = 'approved'`
    ).bind(sh[1]).first();
    const imgs = row ? (await withImages(env, [row]))[0].images : [];
    return new Response(sharePage(row, imgs, url.origin), {
      status: row ? 200 : 404,
      headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' },
    });
  }

  // 개인정보처리방침 — Play 스토어 등록에 쓰는 공개 페이지 (로그인 불필요)
  if (path === '/privacy') {
    return new Response(privacyPage(env.CONTACT_EMAIL || 'woori.church.app@example.com'), {
      headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'public, max-age=3600' },
    });
  }

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
    // 2026-09 간소화: 교회 이름 · 주소 · 교단 · 담임목사 성함 + 신청하시는 분(관리자) 이름과 비밀번호
    const NEED = {
      churchName: '교회 이름을 적어 주세요', address: '교회 주소를 적어 주세요',
      denomination: '소속 교단을 적어 주세요', pastorName: '담임목사님 성함을 적어 주세요',
      pinNumber: '비밀번호를 정해 주세요',
    };
    for (const k of Object.keys(NEED))
      if (!b[k] || String(b[k]).trim() === '') return fail(NEED[k]);
    if (!/^\d{6}$/.test(b.pinNumber)) return fail('비밀번호는 숫자 6자리여야 합니다');
    // 등록하는 분 = 이 교회의 '관리자'. 담임목사님이 직접 하시면 같은 이름입니다.
    const applicant = String(b.applicantName || b.pastorName).trim().slice(0, 30);
    const isPastor = applicant === String(b.pastorName).trim();

    // 1단 — 이단 규정 단체 키워드 자동 거부
    const blocked = await env.DB.prepare('select keyword, note from blocked_keywords').all();
    const haystack = [b.churchName, b.denomination, b.pastorName, applicant, b.address, b.homepage, b.note].join(' ');
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
      b.churchName.trim(), b.address.trim(), (b.phone || '').trim() || null, b.denomination.trim(),
      b.pastorName.trim(), b.seminary || null, b.homepage || null, b.note || null, invitedBy
    ).first();

    try {
      // role 'owner' = 관리자 (담임목사님과 같은 전체 권한)
      await env.DB.prepare(
        `insert into members(church_id,name,pin_hash,pin_salt,email,pw_hash,pw_salt,title,role,share_scope,status)
         values(?,?,?,?,?,?,?,?,'owner','all_cells','active')`
      ).bind(ch.id, applicant, pinHash, salt, b.email || null, pwHash, pwSalt, isPastor ? '목사' : '성도').run();
    } catch (e) {
      // 계정을 못 만들면 교회 줄도 되돌립니다 (주인 없는 교회가 남지 않도록)
      await env.DB.prepare('delete from churches where id = ?').bind(ch.id).run();
      throw e;
    }

    try {
      ctx?.waitUntil((async () => {
        const admins = await platformAdmins(env);
        await pushTo(env, admins, {
          title: '새 교회 등록 신청',
          body: '심사 화면에서 확인해 주세요.',
          link: 'platform', kind: 'admin',
        });
      })());
    } catch { /* 알림 실패는 무시 */ }
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
    try {
      ctx?.waitUntil((async () => {
        const admins = await churchAdmins(env, b.churchId);
        await pushTo(env, admins, {
          title: '새 가입 신청',
          body: `${b.name.trim()}님이 가입을 신청했습니다.`,
          link: 'members', kind: 'admin',
        });
      })());
    } catch { /* 알림 실패는 무시 */ }
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

  return await authed(req, env, url, path, me, P, q, ctx);
}

// ══════════════════════════════════════════════════════════════════════════
//  로그인한 사용자용
// ══════════════════════════════════════════════════════════════════════════
async function authed(req, env, url, path, me, P, q, ctx) {
  // 알림은 응답을 돌려준 뒤 뒤에서 보냅니다 (화면이 기다리지 않게)
  const later = (p) => { try { ctx?.waitUntil(p); } catch { /* 무시 */ } };


  // ── 내 정보 ──────────────────────────────────────────────────────────
  // 홈 화면에 필요한 것을 한 번에 — 왕복을 1번으로 줄여 첫 로딩을 빠르게
  if (P('/bootstrap')) {
    const meData = await meJson(env, me);
    const t = today();
    const [notice, unread, bulletin, wrote, cells] = await env.DB.batch([
      env.DB.prepare(
        `select n.id, n.title, n.body, n.created_at,
                (select count(*) from notice_reads r where r.notice_id = n.id and r.member_id = ?1) as read_flag
           from notices n where n.church_id = ?2 order by n.pinned desc, n.created_at desc limit 1`
      ).bind(me.id, me.church_id),
      env.DB.prepare(
        `select count(*) as c from notices n where n.church_id = ?2
          and not exists (select 1 from notice_reads r where r.notice_id = n.id and r.member_id = ?1)`
      ).bind(me.id, me.church_id),
      env.DB.prepare(
        `select id, title, week_of, mime, size from bulletins where church_id = ?
          order by week_of desc, id desc limit 1`
      ).bind(me.church_id),
      env.DB.prepare('select id from entries where member_id = ? and date = ?').bind(me.id, t),
      env.DB.prepare(
        `select c.id, c.name, c.leader_id, l.name as leader_name,
                (select count(*) from member_cells mc join members m on m.id = mc.member_id
                  where mc.cell_id = c.id and m.status = 'active') as member_count
           from cells c left join members l on l.id = c.leader_id
          where c.church_id = ? order by c.name`
      ).bind(me.church_id),
    ]);
    const n = notice.results[0];
    let pending = 0;
    if (canManage(me)) {
      const p = await env.DB.prepare(
        `select count(*) as c from members where church_id = ? and status = 'pending'`
      ).bind(me.church_id).first();
      pending = p.c;
    }
    return json({
      me: meData,
      home: {
        zoomUrl: me.zoom_url,
        latestNotice: n ? { id: n.id, title: n.title, body: n.body, createdAt: n.created_at, unread: !n.read_flag } : null,
        unreadNoticeCount: unread.results[0]?.c ?? 0,
        latestBulletin: bulletin.results[0] || null,
        pendingApprovals: pending,
        wroteToday: wrote.results.length > 0,
      },
      cells: cells.results,
      progress: await progressFor(env, me),
    });
  }

  if (P('/me')) return json(await meJson(env, me));

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

  // ── 알림 ─────────────────────────────────────────────────────────────
  if (P('/push/register') && req.method === 'POST') {
    const b = await body(req);
    const kind = b.kind === 'fcm' ? 'fcm' : 'web';
    if (!b.token) return fail('토큰이 없습니다');
    await env.DB.prepare('delete from push_tokens where kind = ? and token = ?').bind(kind, b.token).run();
    await env.DB.prepare('insert into push_tokens(member_id, kind, token) values(?,?,?)')
      .bind(me.id, kind, b.token).run();
    return json({ ok: true });
  }

  if (P('/push/unregister') && req.method === 'POST') {
    const b = await body(req);
    if (b.token) await env.DB.prepare('delete from push_tokens where token = ?').bind(b.token).run();
    else await env.DB.prepare('delete from push_tokens where member_id = ?').bind(me.id).run();
    return json({ ok: true });
  }

  if (P('/push/config')) {
    const has = await env.DB.prepare('select count(*) as c from push_tokens where member_id = ?')
      .bind(me.id).first();
    return json({
      vapidPublicKey: env.VAPID_PUBLIC_KEY || null,
      fcmAvailable: !!env.FCM_SERVICE_ACCOUNT,
      registered: has.c > 0,
      notifyShare: !!me.notify_share,
      notifyBoard: !!me.notify_board,
      notifyAdmin: !!me.notify_admin,
    });
  }

  if (P('/push/prefs') && req.method === 'POST') {
    const b = await body(req);
    const sets = [], args = [];
    if (b.share !== undefined) { sets.push('notify_share = ?'); args.push(b.share ? 1 : 0); }
    if (b.board !== undefined) { sets.push('notify_board = ?'); args.push(b.board ? 1 : 0); }
    if (b.admin !== undefined) { sets.push('notify_admin = ?'); args.push(b.admin ? 1 : 0); }
    if (sets.length) {
      args.push(me.id);
      await env.DB.prepare(`update members set ${sets.join(', ')} where id = ?`).bind(...args).run();
    }
    return json({ ok: true });
  }

  // 알림함 — 웹 서비스워커가 내용을 받아 가고, 앱 안에서도 봅니다
  if (P('/notifications')) {
    const r = await env.DB.prepare(
      `select id, title, body, link, read_flag, created_at from notifications
        where member_id = ? order by created_at desc limit 50`
    ).bind(me.id).all();
    return json({ notifications: r.results });
  }

  if (P('/notifications/read') && req.method === 'POST') {
    await env.DB.prepare('update notifications set read_flag = 1 where member_id = ?').bind(me.id).run();
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
              (select count(*) from member_cells mc join members m on m.id = mc.member_id
                where mc.cell_id = c.id and m.status = 'active') as member_count
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
      await env.DB.prepare('insert or ignore into member_cells(member_id, cell_id) values(?,?)')
        .bind(b.memberId, b.cellId).run();
      await env.DB.prepare(
        `update members set cell_id = coalesce(cell_id, ?),
                role = case when role in ('owner','admin') then role else 'leader' end
          where id = ?`
      ).bind(b.cellId, b.memberId).run();
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
    await env.DB.prepare('delete from member_cells where cell_id = ?').bind(b.id).run();
    await env.DB.prepare('update members set cell_id = null where cell_id = ?').bind(b.id).run();
    await env.DB.prepare(
      `update members set cell_id = (select mc.cell_id from member_cells mc where mc.member_id = members.id limit 1)
        where cell_id is null`
    ).run();
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
    const myCells = onlyMyCell ? await myCellIds(env, me.id, me.cell_id) : [];
    if (onlyMyCell && !myCells.length) return json({ members: [], canEdit: false, canGrantRole: false });
    const sql =
      `select m.id, m.name, m.title, m.role, m.share_scope, m.cell_id, m.status, m.created_at,
              (select group_concat(c2.name, ', ') from member_cells mc2 join cells c2 on c2.id = mc2.cell_id
                where mc2.member_id = m.id) as cell_name,
              (select group_concat(mc3.cell_id, ',') from member_cells mc3 where mc3.member_id = m.id) as cell_ids
         from members m
        where m.church_id = ?` + (onlyMyCell ? ' and ' + inCells(myCells) : '') +
      ` order by case m.status when 'pending' then 0 when 'active' then 1 else 2 end, m.name`;
    const st = onlyMyCell
      ? env.DB.prepare(sql).bind(me.church_id, ...myCells)
      : env.DB.prepare(sql).bind(me.church_id);
    const r = await st.all();
    r.results.forEach((x) => {
      x.cellIds = x.cell_ids ? String(x.cell_ids).split(',').map(Number) : [];
      delete x.cell_ids;
    });
    return json({ members: r.results, canEdit: canManage(me), canGrantRole: isOwner(me) });
  }

  if (P('/members/update') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const t = await env.DB.prepare('select * from members where id = ? and church_id = ?').bind(b.memberId, me.church_id).first();
    if (!t) return fail('성도를 찾을 수 없습니다');
    // 관리자(owner) 계정은 관리자만 바꿀 수 있습니다
    if (t.role === 'owner' && !isOwner(me)) return fail('관리자 계정은 관리자만 바꿀 수 있습니다', 403);

    // 관리자가 한 명도 남지 않게 되는 변경은 막습니다
    const losesOwner = t.role === 'owner' &&
      ((b.role !== undefined && b.role !== 'owner') || b.status === 'inactive');
    if (losesOwner && (await ownerCount(env, me.church_id)) <= 1)
      return fail('관리자가 한 명은 있어야 합니다. 다른 분께 먼저 관리자를 맡겨 주세요.');

    const sets = [], args = [], logs = [];
    if (b.title !== undefined && b.title !== t.title) {
      if (!TITLES.includes(b.title)) return fail('알 수 없는 직분입니다');
      sets.push('title = ?'); args.push(b.title); logs.push(`직분 ${t.title} → ${b.title}`);
    }
    // 셀은 여러 개를 한꺼번에 지정할 수 있습니다 (목사·사모가 모든 셀에 걸치는 경우)
    let newCells = null;
    if (Array.isArray(b.cellIds)) newCells = b.cellIds.filter((x) => x);
    else if (b.cellId !== undefined) newCells = b.cellId ? [b.cellId] : [];
    if (newCells) {
      let names = [];
      if (newCells.length) {
        const ok = await env.DB.prepare(
          `select id, name from cells where church_id = ? and id in (${newCells.map(() => '?').join(',')}) order by name`
        ).bind(me.church_id, ...newCells).all();
        if (ok.results.length !== newCells.length) return fail('셀을 찾을 수 없습니다');
        names = ok.results.map((c) => c.name);
        newCells = ok.results.map((c) => c.id);
      }
      const before = await myCellIds(env, t.id, t.cell_id);
      const same = before.length === newCells.length && before.every((x) => newCells.includes(x));
      if (!same) {
        await setMemberCells(env, t.id, newCells);
        logs.push(names.length ? `셀 → ${names.join(', ')}` : '셀에서 뺌');
      }
    }
    if (b.role !== undefined && b.role !== t.role) {
      if (!isOwner(me)) return fail('권한 부여는 관리자만 할 수 있습니다', 403);
      if (!ROLES.includes(b.role)) return fail('줄 수 없는 권한입니다');
      sets.push('role = ?'); args.push(b.role);
      logs.push(`권한 ${ROLE_LABEL[t.role] || t.role} → ${ROLE_LABEL[b.role] || b.role}`);
      // 관리자가 되면 셀을 골라 나눌 수 있게 합니다
      if (b.role === 'owner' && b.shareScope === undefined && t.share_scope !== 'all_cells') {
        sets.push('share_scope = ?'); args.push('all_cells');
      }
    }
    if (b.shareScope !== undefined && b.shareScope !== t.share_scope) {
      if (!isOwner(me)) return fail('나눔 범위는 관리자만 바꿀 수 있습니다', 403);
      if (!SCOPES.includes(b.shareScope)) return fail('알 수 없는 나눔 범위입니다');
      sets.push('share_scope = ?'); args.push(b.shareScope);
      logs.push(`나눔 범위 → ${b.shareScope === 'all_cells' ? '셀 선택 가능' : '본인 셀만'}`);
    }
    if (b.status !== undefined && b.status !== t.status) {
      if (!['active', 'inactive'].includes(b.status)) return fail('알 수 없는 상태입니다');
      sets.push('status = ?'); args.push(b.status); logs.push(`상태 → ${b.status === 'active' ? '사용' : '중지'}`);
      if (b.status === 'inactive') await env.DB.prepare('delete from sessions where member_id = ?').bind(t.id).run();
    }
    if (!sets.length) {
      if (logs.length) {
        await audit(env, me, '성도 정보 변경', t, logs.join(', '));
        later(pushTo(env, [t.id], {
          title: '내 정보가 바뀌었습니다',
          body: logs.join(', '), link: 'home', kind: 'admin',
        }));
      }
      return json({ ok: true });
    }
    args.push(t.id);
    await env.DB.prepare(`update members set ${sets.join(', ')} where id = ?`).bind(...args).run();
    await audit(env, me, '성도 정보 변경', t, logs.join(', '));
    if (logs.length) {
      later(pushTo(env, [t.id], {
        title: '내 정보가 바뀌었습니다',
        body: logs.join(', '), link: 'home', kind: 'admin',
      }));
    }
    return json({ ok: true });
  }

  if (P('/members/approve') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const t = await env.DB.prepare(`select * from members where id = ? and church_id = ? and status='pending'`)
      .bind(b.memberId, me.church_id).first();
    if (!t) return fail('신청을 찾을 수 없습니다');
    if (b.approve) {
      const cellIds = Array.isArray(b.cellIds) ? b.cellIds.filter((x) => x)
        : (b.cellId ? [b.cellId] : []);
      await env.DB.prepare(`update members set status='active' where id = ?`).bind(t.id).run();
      if (cellIds.length) await setMemberCells(env, t.id, cellIds);
      await audit(env, me, '가입 승인', t, null);
      later(pushTo(env, [t.id], {
        title: '가입이 승인되었습니다',
        body: `${me.church_name} 묵상 나눔을 시작하실 수 있어요.`,
        link: 'home', kind: 'admin',
      }));
    } else {
      await env.DB.prepare('delete from members where id = ?').bind(t.id).run();
      await audit(env, me, '가입 거절', t, null);
    }
    return json({ ok: true });
  }

  // 일괄 승인 — items: [{ memberId, cellIds }]
  if (P('/members/approve-batch') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const items = Array.isArray(b.items) ? b.items.slice(0, 200) : [];
    if (!items.length) return fail('승인할 분이 없습니다');
    const valid = new Set((await env.DB.prepare('select id from cells where church_id = ?')
      .bind(me.church_id).all()).results.map((c) => c.id));
    let done = 0;
    const approved = [];
    for (const it of items) {
      const t = await env.DB.prepare(`select * from members where id = ? and church_id = ? and status='pending'`)
        .bind(it.memberId, me.church_id).first();
      if (!t) continue;
      const cellIds = (Array.isArray(it.cellIds) ? it.cellIds : []).filter((x) => valid.has(x));
      await env.DB.prepare(`update members set status='active' where id = ?`).bind(t.id).run();
      if (cellIds.length) await setMemberCells(env, t.id, cellIds);
      await audit(env, me, '가입 승인', t, '일괄 승인');
      approved.push(t.id);
      done++;
    }
    if (approved.length) later(pushTo(env, approved, {
      title: '가입이 승인되었습니다',
      body: `${me.church_name} 묵상 나눔을 시작하실 수 있어요.`,
      link: 'home', kind: 'admin',
    }));
    return json({ ok: true, count: done });
  }

  if (P('/members/reset-pin') && req.method === 'POST') {
    if (!canManage(me)) return fail('권한이 없습니다', 403);
    const b = await body(req);
    const t = await env.DB.prepare('select * from members where id = ? and church_id = ?').bind(b.memberId, me.church_id).first();
    if (!t) return fail('성도를 찾을 수 없습니다');
    if (t.role === 'owner' && !isOwner(me)) return fail('관리자 계정은 관리자만 바꿀 수 있습니다', 403);
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
    if (!isOwner(me)) return fail('줌 링크는 관리자만 설정할 수 있습니다', 403);
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
      `select id, date, title, body, is_public, created_at, updated_at,
              (select group_concat(t.cell_id, ',') from entry_targets t where t.entry_id = entries.id) as target_ids
         from entries where member_id = ? and date between ? and ? order by date desc`
    ).bind(me.id, from, to).all();
    r.results.forEach((x) => {
      x.cellIds = x.target_ids ? String(x.target_ids).split(',').map(Number) : [];
      delete x.target_ids;
    });
    return json({ entries: await withImages(env, r.results) });
  }

  // 묵상 사진 올리기 — 본문에 사진 바이트를 그대로. (앱·웹에서 미리 줄여서 보냅니다)
  // 올린 사진은 글을 저장할 때 imageIds 로 붙입니다.
  if (P('/entries/image') && req.method === 'POST') {
    const mime = (req.headers.get('Content-Type') || '').split(';')[0].trim();
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(mime))
      return fail('jpg, png, webp 사진만 올릴 수 있습니다');
    const buf = await req.arrayBuffer();
    if (buf.byteLength === 0) return fail('사진이 비어 있습니다');
    if (buf.byteLength > MAX_IMAGE_BYTES)
      return fail(`사진이 너무 큽니다 (${(buf.byteLength / 1024 / 1024).toFixed(1)}MB). 3MB 이하만 올릴 수 있습니다.`);

    // 올려 놓고 글에 붙이지 않은 지 하루 넘은 사진은 치웁니다
    const stale = await env.DB.prepare(
      `select id, r2_key from entry_images
        where member_id = ? and entry_id is null and created_at < datetime('now', '-1 day')`
    ).bind(me.id).all();
    for (const s of stale.results) {
      await env.FILES.delete(s.r2_key).catch(() => {});
      await env.DB.prepare('delete from entry_images where id = ?').bind(s.id).run();
    }

    const token = randomHex(16);
    const ext = mime === 'image/png' ? 'png' : mime === 'image/webp' ? 'webp' : 'jpg';
    const key = `church-${me.church_id}/entries/${token}.${ext}`;
    await env.FILES.put(key, buf, { httpMetadata: { contentType: mime } });
    const row = await env.DB.prepare(
      `insert into entry_images(church_id, member_id, token, r2_key, mime, size) values(?,?,?,?,?,?) returning id`
    ).bind(me.church_id, me.id, token, key, mime, buf.byteLength).first();
    return json({ ok: true, id: row.id, url: '/img/' + token });
  }

  if (P('/entries/save') && req.method === 'POST') {
    const b = await body(req);
    const date = (b.date || today()).slice(0, 10);
    // 사진을 붙였으면 글은 없어도 됩니다
    const hasPhotos = Array.isArray(b.imageIds) && b.imageIds.length > 0;
    if ((!b.body || !String(b.body).trim()) && !hasPhotos) return fail('묵상 내용을 적거나 사진을 넣어 주세요');
    b.body = String(b.body || '');

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
      } else targets = await myCellIds(env, me.id, me.cell_id);
    } else {
      // 일반 성도는 본인이 속한 셀(들)에만
      targets = await myCellIds(env, me.id, me.cell_id);
      if (!targets.length) return fail('아직 셀이 정해지지 않았습니다. 목사님께 문의해 주세요.');
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

    // 사진 — imageIds 가 오면 그 순서대로 붙이고, 빠진 사진은 지웁니다
    if (Array.isArray(b.imageIds)) {
      const want = b.imageIds.map(Number).filter((x) => x > 0).slice(0, MAX_IMAGES);
      const had = await env.DB.prepare('select id, r2_key from entry_images where entry_id = ?').bind(entryId).all();
      for (const h of had.results) {
        if (!want.includes(h.id)) {
          await env.FILES.delete(h.r2_key).catch(() => {});
          await env.DB.prepare('delete from entry_images where id = ?').bind(h.id).run();
        }
      }
      for (let i = 0; i < want.length; i++) {
        // 내가 올린 사진만, 아직 다른 글에 붙지 않았거나 이 글에 붙어 있던 것만
        await env.DB.prepare(
          `update entry_images set entry_id = ?, sort = ?
            where id = ? and member_id = ? and (entry_id is null or entry_id = ?)`
        ).bind(entryId, i, want[i], me.id, entryId).run();
      }
    }
    // 처음 올린 글일 때만 알립니다 (고칠 때마다 울리지 않도록)
    if (!exist && (b.isPublic !== false)) {
      later((async () => {
        const who = await membersInCells(env, targets, me.id);
        // 알림 문구에는 내용을 담지 않습니다 (구글·애플 서버를 지나가기 때문)
        await pushTo(env, who, {
          title: '새 묵상 나눔이 있습니다',
          body: `${me.name} ${me.title}님이 묵상을 나누셨어요.`,
          link: 'feed', kind: 'share',
        });
      })());
    }
    return json({ ok: true, id: entryId, cellCount: targets.length });
  }

  // 공유 링크 만들기 — 내 글만. 같은 글은 늘 같은 링크를 돌려줍니다.
  if (P('/entries/share') && req.method === 'POST') {
    const b = await body(req);
    const e = await env.DB.prepare('select id, member_id, is_public from entries where id = ? and church_id = ?')
      .bind(b.entryId, me.church_id).first();
    if (!e) return fail('글을 찾을 수 없습니다');
    if (e.member_id !== me.id) return fail('링크 공유는 내가 쓴 글만 할 수 있습니다', 403);
    if (!e.is_public) return fail('‘나만 보기’ 글은 공유할 수 없습니다. 글을 고쳐 ‘함께 나누기’로 바꿔 주세요.');
    let row = await env.DB.prepare('select token from entry_shares where entry_id = ?').bind(e.id).first();
    if (!row) {
      row = { token: randomHex(12) };
      await env.DB.prepare('insert into entry_shares(entry_id, token, member_id) values(?,?,?)')
        .bind(e.id, row.token, me.id).run();
    }
    return json({ ok: true, url: `${url.origin}/s/${row.token}` });
  }

  if (P('/entries/delete') && req.method === 'POST') {
    const b = await body(req);
    const e = await env.DB.prepare('select * from entries where id = ?').bind(b.id).first();
    if (!e) return fail('글을 찾을 수 없습니다');
    if (e.member_id !== me.id) return fail('본인 글만 지울 수 있습니다', 403);
    const imgs = await env.DB.prepare('select r2_key from entry_images where entry_id = ?').bind(b.id).all();
    for (const x of imgs.results) await env.FILES.delete(x.r2_key).catch(() => {});
    await env.DB.prepare('delete from entry_images where entry_id = ?').bind(b.id).run();
    await env.DB.prepare('delete from entry_shares where entry_id = ?').bind(b.id).run();
    await env.DB.prepare('delete from entries where id = ?').bind(b.id).run();
    return json({ ok: true });
  }

  // 오늘의 나눔 (scope=cell 우리 셀 / all 전체)
  if (P('/entries/feed')) {
    const date = q('date') || today();
    const scope = q('scope') === 'all' ? 'all' : 'cell';
    const cells = await visibleCellIds(env, me, scope);
    const rows = await feedQuery(env, me, { date, scope, cells });
    return json({ entries: await withImages(env, rows), scope });
  }

  // 출석표 (분기 단위)
  // range=year : 이번 달을 맨 오른쪽으로 최근 12개월 (2026-09 새 출석표)
  // 그 외     : 예전 앱을 위한 분기 단위
  if (P('/entries/attendance')) {
    const scope = q('scope') === 'all' ? 'all' : 'cell';
    let year = parseInt(q('year') || today().slice(0, 4), 10);
    let quarter = parseInt(q('quarter') || String(Math.floor(new Date().getMonth() / 3) + 1), 10);
    let from, to;
    if (q('range') === 'year') {
      const t = today();
      const y = parseInt(t.slice(0, 4), 10), m = parseInt(t.slice(5, 7), 10);
      const sm = m - 11 <= 0 ? m + 1 : m - 11, sy = m - 11 <= 0 ? y - 1 : y;
      from = `${sy}-${String(sm).padStart(2, '0')}-01`;
      to = t;
    } else {
      const startMonth = (quarter - 1) * 3 + 1;
      from = `${year}-${String(startMonth).padStart(2, '0')}-01`;
      const endMonth = startMonth + 2;
      const lastDay = new Date(year, endMonth, 0).getDate();
      to = `${year}-${String(endMonth).padStart(2, '0')}-${lastDay}`;
    }

    // cellId 가 오면 그 셀(구역) 사람만 — 출석표를 구역별로 나눠 봅니다
    let cells = await visibleCellIds(env, me, scope);
    let where = scope === 'all' ? 'm.church_id = ?' : (cells.length ? inCells(cells) : '0');
    let args = scope === 'all' ? [me.church_id] : cells;
    const cellId = parseInt(q('cellId') || '0', 10);
    if (cellId) {
      const ok = await env.DB.prepare('select id from cells where id = ? and church_id = ?').bind(cellId, me.church_id).first();
      if (!ok) return fail('구역을 찾을 수 없습니다');
      where = inCells([cellId]); args = [cellId];
    }
    if (where === '0') return json({ from, to, people: [], year, quarter });

    const people = await env.DB.prepare(
      `select m.id, m.name, m.title,
              (select group_concat(c2.name, ', ') from member_cells mc2 join cells c2 on c2.id = mc2.cell_id
                where mc2.member_id = m.id) as cell_name
         from members m
        where ${where} and m.status = 'active' order by m.name`
    ).bind(...args).all();
    const marks = await env.DB.prepare(
      `select e.member_id, e.date from entries e
         join members m on m.id = e.member_id
        where ${where} and e.date between ? and ?`
    ).bind(...args, from, to).all();
    const byMember = {};
    for (const row of marks.results) (byMember[row.member_id] ||= []).push(row.date);
    await shortenCells(env, me.church_id, people.results);
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
    let sql = `select e.id, e.date, e.title, e.body, e.is_public, e.member_id,
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
    return json({ person: t, total: total.c, entries: await withImages(env, r.results) });
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
      sql += ` and (${inCells(cells)} or e.member_id = ?)`;
      args.push(...cells, me.id);
    }
    if (term) { sql += ' and (e.title like ? or e.body like ?)'; args.push('%' + term + '%', '%' + term + '%'); }
    if (memberId) { sql += ' and e.member_id = ?'; args.push(parseInt(memberId, 10)); }
    if (from) { sql += ' and e.date >= ?'; args.push(from); }
    if (to) { sql += ' and e.date <= ?'; args.push(to); }
    sql += ' order by e.date desc limit 100';
    const r = await env.DB.prepare(sql).bind(...args).all();
    return json({ entries: await withImages(env, r.results) });
  }

  // 나의 묵상 완료율
  if (P('/entries/progress'))
    return json(await progressFor(env, me, parseInt(q('year') || today().slice(0, 4), 10)));

  // 전체 묵상 기록 내려받기
  if (P('/entries/export')) {
    const scope = q('scope') === 'church' ? 'church' : 'mine';
    if (scope === 'church' && !canManage(me)) return fail('권한이 없습니다', 403);
    const r = scope === 'church'
      ? await env.DB.prepare(
          `select e.date, e.title, e.body, e.is_public, m.name as member_name, m.title as member_title
             from entries e join members m on m.id = e.member_id
            where e.church_id = ? and e.is_public = 1 order by e.date desc, m.name`
        ).bind(me.church_id).all()
      : await env.DB.prepare(
          `select date, title, body, is_public from entries where member_id = ? order by date desc`
        ).bind(me.id).all();
    return json({ scope, churchName: me.church_name, name: me.name, entries: r.results });
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
    const owner = await env.DB.prepare('select member_id from entries where id = ?').bind(b.entryId).first();
    if (owner && owner.member_id !== me.id) {
      later(pushTo(env, [owner.member_id], {
        title: '내 묵상에 댓글이 달렸어요',
        body: `${me.name} ${me.title}님이 댓글을 남기셨습니다.`,
        link: 'feed', kind: 'share',
      }));
    }
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
    const mine = await myCellIds(env, me.id, me.cell_id);
    const cellId = parseInt(q('cellId') || mine[0] || 0, 10);
    if (!cellId) return fail('셀이 없습니다');
    if (!canManage(me) && !mine.includes(cellId)) return fail('권한이 없습니다', 403);
    const date = q('date') || today();
    const r = await env.DB.prepare(
      `select m.id, m.name, m.title,
              (select count(*) from entries e where e.member_id = m.id and e.date = ?) as wrote_today,
              (select count(*) from entries e where e.member_id = m.id and e.date >= date(?, '-6 day') and e.date <= ?) as week_count,
              (select max(e.date) from entries e where e.member_id = m.id) as last_date
         from members m
        where m.status = 'active'
          and exists (select 1 from member_cells mc where mc.member_id = m.id and mc.cell_id = ?)
        order by m.name`
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
    } else {
      targets = await myCellIds(env, me.id, me.cell_id);
      if (!targets.length) return fail('아직 셀이 정해지지 않았습니다. 목사님께 문의해 주세요.');
    }

    const row = await env.DB.prepare('insert into prayers(church_id, member_id, body) values(?,?,?) returning id')
      .bind(me.church_id, me.id, b.body.trim()).first();
    for (const cid of targets)
      await env.DB.prepare('insert or ignore into prayer_targets(prayer_id, cell_id) values(?,?)').bind(row.id, cid).run();
    later((async () => {
      const who = await membersInCells(env, targets, me.id);
      await pushTo(env, who, {
        title: '새 기도제목이 있습니다',
        body: `${me.name} ${me.title}님이 기도제목을 올리셨어요.`,
        link: 'prayer', kind: 'share',
      });
    })());
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
      later((async () => {
        const who = await membersInChurch(env, me.church_id, me.id);
        await pushTo(env, who, {
          title: '새 공지사항이 올라왔습니다',
          body: '앱에서 확인해 주세요.',
          link: 'notice', kind: 'board',
        });
      })());
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
    later((async () => {
      const who = await membersInChurch(env, me.church_id, me.id);
      await pushTo(env, who, {
        title: '이번 주 주보가 올라왔어요',
        body: `${weekOf.slice(5, 7)}월 ${weekOf.slice(8, 10)}일 주보`,
        link: 'bulletin', kind: 'board',
      });
    })());
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
    // 앱 자체에 대한 건의이므로 앱 운영자만 봅니다 (교회 목사도 볼 수 없습니다)
    if (!me.is_platform_admin) return fail('권한이 없습니다', 403);
    const r = await env.DB.prepare(
      `select s.id, s.name, s.body, s.is_read, s.created_at, c.name as church_name
         from suggestions s left join churches c on c.id = s.church_id
        order by s.created_at desc limit 300`
    ).all();
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
                (select name from churches p where p.id = c.invited_by) as invited_by_name,
                (select group_concat(m.name, ', ') from members m
                  where m.church_id = c.id and m.role = 'owner') as applicant_name
           from churches c where c.status = ? order by c.created_at desc limit 200`
      ).bind(status).all();
      return json({ churches: r.results });
    }

    if (P('/platform/deletions')) {
      const r = await env.DB.prepare(
        `select id, church_name, member_name, contact, note, status, created_at
           from deletion_requests order by status, created_at desc limit 200`
      ).all();
      return json({ requests: r.results });
    }

    if (P('/platform/deletions/done') && req.method === 'POST') {
      const b = await body(req);
      await env.DB.prepare(
        "update deletion_requests set status = ?, handled_at = datetime('now') where id = ?"
      ).bind(b.status === 'rejected' ? 'rejected' : 'done', b.id).run();
      return json({ ok: true });
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

// ── 로그인한 사람 정보 (속한 셀들 포함) ────────────────────────────────────
async function meJson(env, me) {
  const r = await env.DB.prepare(
    `select c.id, c.name from member_cells mc join cells c on c.id = mc.cell_id
      where mc.member_id = ? order by c.name`
  ).bind(me.id).all();
  let cells = r.results;
  if (!cells.length && me.cell_id) cells = [{ id: me.cell_id, name: me.cell_name }];
  const full = cells.map((c) => c.name).join(', ');
  const label = cells.length > 1 ? shortCellLabel(full, await churchCellNames(env, me.church_id)) : full;
  return {
    id: me.id, name: me.name, churchId: me.church_id, churchName: me.church_name,
    title: me.title, role: me.role, shareScope: me.share_scope,
    cellId: cells[0]?.id ?? null,
    cellName: label || null,            // 화면 표시용 짧은 이름 (예: '전체 구역', '1구역 외 2')
    cellNames: full || null,            // 전체 이름 목록
    cellIds: cells.map((c) => c.id),
    zoomUrl: me.zoom_url,
    mustChangePin: !!me.must_change_pin,
    isPlatformAdmin: !!me.is_platform_admin,
  };
}

// ── 묵상 완료율 계산 ─────────────────────────────────────────────────────
// 분모는 '오늘까지 지난 날 수'가 아니라 기간 전체 날 수입니다.
//   이번 분기 = 그 분기 3달 전체(90~92일) 중 며칠 했는지
//   올해     = 1년 전체(365·366일) 중 며칠 했는지
async function progressFor(env, me, year) {
  year = year || parseInt(today().slice(0, 4), 10);
  const rows = await env.DB.prepare(
    `select date from entries where member_id = ? and substr(date,1,4) = ?`
  ).bind(me.id, String(year)).all();
  const dates = rows.results.map((r) => r.date);
  // 달의 마지막 날 (UTC 로 계산해 서버 시간대와 무관하게)
  const monthDays = (m) => new Date(Date.UTC(year, m, 0)).getUTCDate();
  const pct = (done, total) => (total ? Math.min(100, Math.round((done / total) * 100)) : 0);
  const quarters = [];
  for (let qi = 1; qi <= 4; qi++) {
    const sm = (qi - 1) * 3 + 1, em = sm + 2;
    const days = monthDays(sm) + monthDays(sm + 1) + monthDays(em);
    const done = dates.filter((d) => {
      const m = parseInt(d.slice(5, 7), 10);
      return m >= sm && m <= em;
    }).length;
    quarters.push({ quarter: qi, done, total: days, percent: pct(done, days) });
  }
  const yDays = quarters.reduce((a, q) => a + q.total, 0);
  return {
    year, quarters,
    annual: { done: dates.length, total: yDays, percent: pct(dates.length, yDays) },
  };
}

// ── 글마다 붙은 사진 목록을 images: [{id, url}] 로 붙입니다 ─────────────────
async function withImages(env, rows) {
  rows.forEach((r) => { r.images = []; });
  const ids = rows.map((r) => r.id);
  if (!ids.length) return rows;
  const byId = new Map(rows.map((r) => [r.id, r]));
  // D1 은 한 번에 묶을 수 있는 값 개수에 한도가 있어 나눠서 묻습니다
  for (let i = 0; i < ids.length; i += 90) {
    const part = ids.slice(i, i + 90);
    const r = await env.DB.prepare(
      `select id, entry_id, token from entry_images
        where entry_id in (${part.map(() => '?').join(',')}) order by entry_id, sort, id`
    ).bind(...part).all();
    for (const x of r.results) byId.get(x.entry_id)?.images.push({ id: x.id, url: '/img/' + x.token });
  }
  return rows;
}

// ── 나눔 목록 공통 질의 ──────────────────────────────────────────────────
async function feedQuery(env, me, { date, scope, cells }) {
  let sql = `select e.id, e.date, e.title, e.body, e.created_at,
                    m.id as member_id, m.name as member_name, m.title as member_title,
                    (select group_concat(c2.name, ', ') from member_cells mc2 join cells c2 on c2.id = mc2.cell_id
                      where mc2.member_id = m.id) as cell_name,
                    (select count(*) from likes l where l.entry_id = e.id) as like_count,
                    (select count(*) from likes l where l.entry_id = e.id and l.member_id = ?) as liked,
                    (select count(*) from comments x where x.entry_id = e.id) as comment_count
               from entries e
               join members m on m.id = e.member_id
              where e.church_id = ? and e.date = ? and (e.is_public = 1 or e.member_id = ?)`;
  const args = [me.id, me.church_id, date, me.id];
  if (scope !== 'all') {
    if (!cells.length) return [];
    // 이 글이 내 셀로 나눠졌거나, 글쓴이가 내 셀 사람이거나, 내 글
    sql += ` and ( exists (select 1 from entry_targets t where t.entry_id = e.id and t.cell_id in (${cells.map(() => '?').join(',')}))
                   or exists (select 1 from member_cells mc where mc.member_id = m.id and mc.cell_id in (${cells.map(() => '?').join(',')}))
                   or e.member_id = ? )`;
    args.push(...cells, ...cells, me.id);
  }
  sql += ' order by e.created_at desc limit 200';
  const r = await env.DB.prepare(sql).bind(...args).all();
  return shortenCells(env, me.church_id, r.results);
}
