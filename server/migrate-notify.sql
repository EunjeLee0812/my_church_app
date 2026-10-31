-- ─────────────────────────────────────────────────────────────────────────
-- 이미 쓰고 있는 데이터베이스에 알림 설정 칸을 더합니다.
--   npx wrangler d1 execute woori-church --remote --file=./migrate-notify.sql
-- ※ 딱 한 번만 실행하세요. 두 번 돌리면 '이미 있는 칸'이라고 오류가 납니다
--   (그 오류가 나면 이미 적용된 것이니 무시하셔도 됩니다).
-- ─────────────────────────────────────────────────────────────────────────
alter table members add column notify_share integer not null default 1;
alter table members add column notify_board integer not null default 1;
alter table members add column notify_admin integer not null default 1;
