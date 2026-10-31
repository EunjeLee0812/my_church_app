-- ─────────────────────────────────────────────────────────────────────────
-- 2026-09 기능 개선용 (묵상 사진)
--   npx wrangler d1 execute woori-church --remote --file=./migrate-2026-09.sql
-- 여러 번 실행해도 안전합니다. (schema.sql 을 다시 돌려도 같은 결과입니다)
-- ─────────────────────────────────────────────────────────────────────────
create table if not exists entry_images (
  id         integer primary key autoincrement,
  church_id  integer not null,
  member_id  integer not null references members(id) on delete cascade,
  entry_id   integer references entries(id) on delete cascade,
  token      text not null,
  r2_key     text not null,
  mime       text not null,
  size       integer not null,
  sort       integer not null default 0,
  created_at text not null default (datetime('now'))
);
create unique index if not exists entry_images_token on entry_images(token);
create index if not exists entry_images_entry on entry_images(entry_id, sort);
create index if not exists entry_images_member on entry_images(member_id, entry_id);

-- 묵상 공유 링크 (2차 추가분)
create table if not exists entry_shares (
  entry_id   integer primary key references entries(id) on delete cascade,
  token      text not null,
  member_id  integer not null,
  created_at text not null default (datetime('now'))
);
create unique index if not exists entry_shares_token on entry_shares(token);
