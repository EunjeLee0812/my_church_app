// ═══════════════════════════════════════════════════════════════════════════
//  알림 보내기
//   · 안드로이드 앱  → FCM (Firebase Cloud Messaging)
//   · 웹앱/아이폰    → Web Push (VAPID). 내용 없이 깨우기만 하고,
//                      서비스워커가 서버에서 내용을 받아 갑니다.
//  둘 다 설정이 없으면 조용히 건너뜁니다 (앱 동작에는 지장 없음).
// ═══════════════════════════════════════════════════════════════════════════

const b64url = (buf) =>
  btoa(String.fromCharCode(...new Uint8Array(buf)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const textBytes = (s) => new TextEncoder().encode(s);

function pemToBuf(pem) {
  const body = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const bin = atob(body);
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

// ── FCM ────────────────────────────────────────────────────────────────────
let fcmToken = null;   // { value, exp }

async function fcmAccessToken(env) {
  if (fcmToken && fcmToken.exp > Date.now() + 60_000) return fcmToken.value;
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(textBytes(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = b64url(textBytes(JSON.stringify({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now, exp: now + 3600,
  })));
  const key = await crypto.subtle.importKey(
    'pkcs8', pemToBuf(sa.private_key),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = b64url(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, textBytes(`${header}.${claims}`)));
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${header}.${claims}.${sig}`,
    }),
  });
  if (!res.ok) throw new Error('FCM 토큰 실패: ' + (await res.text()).slice(0, 200));
  const j = await res.json();
  fcmToken = { value: j.access_token, exp: Date.now() + (j.expires_in - 120) * 1000 };
  return fcmToken.value;
}

async function sendFcm(env, token, { title, body, link }) {
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const access = await fcmAccessToken(env);
  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
    {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + access, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: {
          token,
          notification: { title, body },
          data: { link: link || '' },
          android: { priority: 'HIGH', notification: { channel_id: 'woori', default_sound: true } },
        },
      }),
    }
  );
  // 404/403 이면 더 이상 쓸 수 없는 토큰입니다
  if (res.status === 404 || res.status === 403) return 'gone';
  return res.ok ? 'ok' : 'fail';
}

// ── Web Push (VAPID, 내용 없는 깨우기) ──────────────────────────────────────
async function vapidHeader(env, endpoint) {
  const jwk = JSON.parse(env.VAPID_PRIVATE_JWK);
  const aud = new URL(endpoint).origin;
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(textBytes(JSON.stringify({ alg: 'ES256', typ: 'JWT' })));
  const claims = b64url(textBytes(JSON.stringify({
    aud, exp: now + 12 * 3600, sub: env.VAPID_SUBJECT || 'mailto:admin@example.com',
  })));
  const key = await crypto.subtle.importKey(
    'jwk', jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
  const sig = b64url(await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' }, key, textBytes(`${header}.${claims}`)));
  return `vapid t=${header}.${claims}.${sig}, k=${env.VAPID_PUBLIC_KEY}`;
}

async function sendWeb(env, endpoint) {
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: {
      TTL: '86400',
      Urgency: 'normal',
      Authorization: await vapidHeader(env, endpoint),
      'Content-Length': '0',
    },
  });
  if (res.status === 404 || res.status === 410) return 'gone';
  return res.ok || res.status === 201 ? 'ok' : 'fail';
}

// ── 한 사람에게 보내기 ──────────────────────────────────────────────────────
export async function pushTo(env, memberIds, { title, body, link, kind }) {
  const ids = [...new Set(memberIds)].filter(Boolean);
  if (!ids.length) return;
  const placeholders = ids.map(() => '?').join(',');

  // 알림 종류별 설정 확인
  const col = kind === 'board' ? 'notify_board' : kind === 'admin' ? 'notify_admin' : 'notify_share';
  const targets = await env.DB.prepare(
    `select id from members where id in (${placeholders}) and ${col} = 1 and status = 'active'`
  ).bind(...ids).all();
  const want = targets.results.map((r) => r.id);
  if (!want.length) return;

  // 기록으로 남깁니다 (웹앱이 내용을 받아 가고, 앱 안 알림함에도 쓰입니다)
  for (const id of want) {
    await env.DB.prepare(
      'insert into notifications(member_id, title, body, link) values(?,?,?,?)'
    ).bind(id, title, body, link || null).run();
  }

  const rows = await env.DB.prepare(
    `select id, member_id, kind, token from push_tokens where member_id in (${want.map(() => '?').join(',')})`
  ).bind(...want).all();

  const hasFcm = !!env.FCM_SERVICE_ACCOUNT;
  const hasVapid = !!env.VAPID_PRIVATE_JWK && !!env.VAPID_PUBLIC_KEY;

  for (const t of rows.results) {
    try {
      let r = 'skip';
      if (t.kind === 'fcm' && hasFcm) r = await sendFcm(env, t.token, { title, body, link });
      else if (t.kind === 'web' && hasVapid) r = await sendWeb(env, t.token);
      if (r === 'gone') {
        await env.DB.prepare('delete from push_tokens where id = ?').bind(t.id).run();
      } else if (r === 'ok') {
        await env.DB.prepare('update push_tokens set last_ok = datetime(\'now\') where id = ?')
          .bind(t.id).run();
      }
    } catch (_) { /* 알림 실패가 본 작업을 막지 않게 합니다 */ }
  }
}

// ── 대상 고르기 ────────────────────────────────────────────────────────────
/** 특정 셀들에 속한 사람들 (본인 제외) */
export async function membersInCells(env, cellIds, exceptId) {
  if (!cellIds.length) return [];
  const r = await env.DB.prepare(
    `select distinct m.id from members m
       join member_cells mc on mc.member_id = m.id
      where mc.cell_id in (${cellIds.map(() => '?').join(',')})
        and m.status = 'active' and m.id <> ?`
  ).bind(...cellIds, exceptId || 0).all();
  return r.results.map((x) => x.id);
}

/** 교회 전체 */
export async function membersInChurch(env, churchId, exceptId) {
  const r = await env.DB.prepare(
    `select id from members where church_id = ? and status = 'active' and id <> ?`
  ).bind(churchId, exceptId || 0).all();
  return r.results.map((x) => x.id);
}

/** 그 교회의 관리 권한자 */
export async function churchAdmins(env, churchId) {
  const r = await env.DB.prepare(
    `select id from members where church_id = ? and status = 'active' and role in ('owner','admin')`
  ).bind(churchId).all();
  return r.results.map((x) => x.id);
}

/** 앱 운영자 */
export async function platformAdmins(env) {
  const r = await env.DB.prepare(
    `select id from members where is_platform_admin = 1 and status = 'active'`
  ).all();
  return r.results.map((x) => x.id);
}
