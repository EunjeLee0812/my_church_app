// ═══════════════════════════════════════════════════════════════════════════
//  기존 Supabase(나눔광장) 데이터를 새 D1 로 옮기기
//
//  쓰는 법:
//    1) Supabase → Settings → API 에서 service_role 키를 복사 (한 번만 쓰고 버립니다)
//    2) node migrate-from-supabase.mjs \
//         --url https://vadzwqrlsjatogxrmony.supabase.co \
//         --key <service_role 키> \
//         --church 1 \
//         --cell "본교회"
//    3) 만들어진 migrate.sql 을 확인한 뒤
//         npx wrangler d1 execute woori-church --remote --file=./migrate.sql
//
//  * --church 는 새 앱에서 만든 교회 id, --cell 은 옮긴 사람들을 넣을 셀 이름입니다.
//  * 기존 4자리 비밀번호는 방식이 달라 그대로 못 옮깁니다.
//    옮긴 성도는 모두 '비밀번호 초기화' 상태가 되고, 목사님이 임시 번호를 알려 주면 됩니다.
// ═══════════════════════════════════════════════════════════════════════════

import { writeFileSync } from 'node:fs';

const arg = (name, dflt = null) => {
  const i = process.argv.indexOf('--' + name);
  return i > -1 ? process.argv[i + 1] : dflt;
};

const SUPA_URL = arg('url');
const SUPA_KEY = arg('key');
const CHURCH_ID = parseInt(arg('church', '1'), 10);
const CELL_NAME = arg('cell', '본교회');

if (!SUPA_URL || !SUPA_KEY) {
  console.error('사용법: node migrate-from-supabase.mjs --url <supabase url> --key <service_role key> --church <교회 id> [--cell <셀 이름>]');
  process.exit(1);
}

const q = (s) => "'" + String(s).replace(/'/g, "''") + "'";

async function table(name) {
  const res = await fetch(`${SUPA_URL}/rest/v1/${name}?select=*`, {
    headers: { apikey: SUPA_KEY, Authorization: `Bearer ${SUPA_KEY}` },
  });
  if (!res.ok) throw new Error(`${name} 을(를) 읽지 못했습니다: ${res.status} ${await res.text()}`);
  return res.json();
}

const members = await table('members');
const entries = await table('entries');
let comments = [];
let likes = [];
try { comments = await table('comments'); } catch { console.warn('댓글 표가 없어 건너뜁니다'); }
try { likes = await table('likes'); } catch { console.warn('좋아요 표가 없어 건너뜁니다'); }

console.log(`성도 ${members.length}명 · 묵상 ${entries.length}개 · 댓글 ${comments.length} · 좋아요 ${likes.length}`);

const lines = [];
lines.push('-- 기존 나눔광장 데이터 이전. 한 번만 실행하세요.');
lines.push('begin transaction;');
lines.push(`insert or ignore into cells(church_id, name) values(${CHURCH_ID}, ${q(CELL_NAME)});`);

// 성도 — 비밀번호는 옮기지 않고 초기화 상태로 넣습니다
for (const m of members) {
  lines.push(
    `insert or ignore into members(church_id, name, title, role, share_scope, status, must_change_pin, cell_id) ` +
    `values(${CHURCH_ID}, ${q(m.name)}, '성도', ${m.is_admin ? "'admin'" : "'member'"}, 'own_cell', 'active', 1, ` +
    `(select id from cells where church_id = ${CHURCH_ID} and name = ${q(CELL_NAME)}));`
  );
}

// 묵상
for (const e of entries) {
  const author = members.find((m) => m.hex === e.hex);
  if (!author) continue;
  lines.push(
    `insert or ignore into entries(church_id, member_id, date, title, body, is_public, created_at) ` +
    `values(${CHURCH_ID}, (select id from members where church_id = ${CHURCH_ID} and name = ${q(author.name)}), ` +
    `${q(e.date)}, ${e.title ? q(e.title) : 'null'}, ${q(e.body)}, ${e.is_public ? 1 : 0}, ${q(e.created_at ?? '')});`
  );
}

// 모든 묵상을 옮긴 셀로 나눔 처리
lines.push(
  `insert or ignore into entry_targets(entry_id, cell_id) ` +
  `select e.id, (select id from cells where church_id = ${CHURCH_ID} and name = ${q(CELL_NAME)}) ` +
  `from entries e where e.church_id = ${CHURCH_ID};`
);

// 댓글
for (const c of comments) {
  const author = members.find((m) => m.hex === c.hex);
  const owner = members.find((m) => m.hex === c.entry_hex);
  if (!author || !owner) continue;
  lines.push(
    `insert into comments(entry_id, member_id, body, created_at) ` +
    `select e.id, (select id from members where church_id = ${CHURCH_ID} and name = ${q(author.name)}), ` +
    `${q(c.body)}, ${q(c.created_at ?? '')} from entries e ` +
    `where e.church_id = ${CHURCH_ID} and e.date = ${q(c.entry_date)} ` +
    `and e.member_id = (select id from members where church_id = ${CHURCH_ID} and name = ${q(owner.name)});`
  );
}

// 좋아요
for (const l of likes) {
  const liker = members.find((m) => m.hex === l.hex);
  const owner = members.find((m) => m.hex === l.entry_hex);
  if (!liker || !owner) continue;
  lines.push(
    `insert or ignore into likes(entry_id, member_id) ` +
    `select e.id, (select id from members where church_id = ${CHURCH_ID} and name = ${q(liker.name)}) ` +
    `from entries e where e.church_id = ${CHURCH_ID} and e.date = ${q(l.entry_date)} ` +
    `and e.member_id = (select id from members where church_id = ${CHURCH_ID} and name = ${q(owner.name)});`
  );
}

lines.push('commit;');
writeFileSync('migrate.sql', lines.join('\n'), 'utf8');
console.log(`migrate.sql 을 만들었습니다 (${lines.length}줄).`);
console.log('확인한 뒤: npx wrangler d1 execute woori-church --remote --file=./migrate.sql');
