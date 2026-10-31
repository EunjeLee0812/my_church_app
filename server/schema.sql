-- ─────────────────────────────────────────────────────────────────────────
-- 우리교회 · Cloudflare D1 스키마
--   wrangler d1 execute woori-church --remote --file=./schema.sql
--   (여러 번 실행해도 안전합니다)
-- ─────────────────────────────────────────────────────────────────────────

-- 교회 ---------------------------------------------------------------------
create table if not exists churches (
  id            integer primary key autoincrement,
  name          text not null,
  address       text,
  phone         text,
  denomination  text,              -- 소속 교단
  pastor_name   text,              -- 담임목사 성함
  seminary      text,              -- 졸업 신학교 / 안수 교단
  homepage      text,              -- 홈페이지 또는 교단 교회검색 링크
  note          text,              -- 신청자 메모
  status        text not null default 'pending',   -- pending / approved / rejected / suspended
  reject_reason text,              -- 내부 기록용. 신청자에게 노출하지 않음
  zoom_url      text,
  invite_code   text,              -- 이 교회가 다른 교회를 초대할 때 쓰는 코드
  invited_by    integer,           -- 초대해 준 교회 id
  created_at    text not null default (datetime('now')),
  reviewed_at   text
);
create index if not exists churches_status on churches(status);
create unique index if not exists churches_invite on churches(invite_code) where invite_code is not null;

-- 성도 ---------------------------------------------------------------------
create table if not exists members (
  id           integer primary key autoincrement,
  church_id    integer not null references churches(id),
  name         text not null,
  pin_hash     text,               -- 숫자 6자리 (PBKDF2)
  pin_salt     text,
  email        text,               -- 관리 권한자용 로그인
  pw_hash      text,
  pw_salt      text,
  title        text not null default '성도',      -- 목사/사모/장로/안수집사/권사/집사/성도
  role         text not null default 'member',    -- owner / admin / leader / member
  share_scope  text not null default 'own_cell',  -- own_cell / all_cells
  cell_id      integer references cells(id),
  status       text not null default 'pending',   -- pending / active / inactive
  is_platform_admin integer not null default 0,   -- 교회 등록 신청을 심사하는 운영자
  must_change_pin   integer not null default 0,   -- 초기화된 비밀번호로 들어온 경우
  fail_count   integer not null default 0,
  locked_until text,
  notify_share integer not null default 1,      -- 나눔·기도제목 알림
  notify_board integer not null default 1,      -- 공지·주보 알림
  notify_admin integer not null default 1,      -- 가입 신청 등 관리 알림
  created_at   text not null default (datetime('now'))
);
create unique index if not exists members_church_name on members(church_id, name);
create unique index if not exists members_email on members(email) where email is not null;
create index if not exists members_cell on members(cell_id);
create index if not exists members_status on members(church_id, status);

-- 셀 -----------------------------------------------------------------------
create table if not exists cells (
  id         integer primary key autoincrement,
  church_id  integer not null references churches(id),
  name       text not null,
  leader_id  integer references members(id),
  created_at text not null default (datetime('now'))
);
create unique index if not exists cells_church_name on cells(church_id, name);

-- 묵상 ---------------------------------------------------------------------
create table if not exists entries (
  id         integer primary key autoincrement,
  church_id  integer not null,
  member_id  integer not null references members(id),
  date       text not null,                      -- YYYY-MM-DD
  title      text,
  body       text not null,
  is_public  integer not null default 1,
  created_at text not null default (datetime('now')),
  updated_at text not null default (datetime('now'))
);
create unique index if not exists entries_member_date on entries(member_id, date);
create index if not exists entries_church_date on entries(church_id, date desc);

-- 이 묵상을 어느 셀에 나눴는가. 행이 없으면 '본인 셀' 로 간주
create table if not exists entry_targets (
  entry_id integer not null references entries(id) on delete cascade,
  cell_id  integer not null references cells(id),
  primary key (entry_id, cell_id)
);

create table if not exists likes (
  entry_id  integer not null references entries(id) on delete cascade,
  member_id integer not null references members(id),
  primary key (entry_id, member_id)
);

create table if not exists comments (
  id         integer primary key autoincrement,
  entry_id   integer not null references entries(id) on delete cascade,
  member_id  integer not null references members(id),
  body       text not null,
  created_at text not null default (datetime('now'))
);
create index if not exists comments_entry on comments(entry_id, created_at);

-- 기도제목 -------------------------------------------------------------------
create table if not exists prayers (
  id         integer primary key autoincrement,
  church_id  integer not null,
  member_id  integer not null references members(id),
  body       text not null,
  answered   integer not null default 0,
  created_at text not null default (datetime('now'))
);
create index if not exists prayers_church on prayers(church_id, created_at desc);

create table if not exists prayer_targets (
  prayer_id integer not null references prayers(id) on delete cascade,
  cell_id   integer not null references cells(id),
  primary key (prayer_id, cell_id)
);

create table if not exists prayer_amens (
  prayer_id integer not null references prayers(id) on delete cascade,
  member_id integer not null references members(id),
  primary key (prayer_id, member_id)
);

-- 공지사항 -------------------------------------------------------------------
create table if not exists notices (
  id         integer primary key autoincrement,
  church_id  integer not null,
  author_id  integer not null references members(id),
  title      text,
  body       text not null,
  pinned     integer not null default 0,
  created_at text not null default (datetime('now'))
);
create index if not exists notices_church on notices(church_id, created_at desc);

create table if not exists notice_reads (
  notice_id integer not null references notices(id) on delete cascade,
  member_id integer not null references members(id),
  read_at   text not null default (datetime('now')),
  primary key (notice_id, member_id)
);

-- 주보 ---------------------------------------------------------------------
create table if not exists bulletins (
  id          integer primary key autoincrement,
  church_id   integer not null,
  title       text,
  week_of     text not null,        -- 그 주 주일 날짜 YYYY-MM-DD
  r2_key      text not null,
  mime        text not null,
  size        integer not null,
  uploaded_by integer references members(id),
  created_at  text not null default (datetime('now'))
);
create index if not exists bulletins_church on bulletins(church_id, week_of desc);

-- 로그인 세션 ----------------------------------------------------------------
create table if not exists sessions (
  token      text primary key,
  member_id  integer not null references members(id),
  created_at text not null default (datetime('now')),
  last_seen  text not null default (datetime('now'))
);
create index if not exists sessions_member on sessions(member_id);

-- 변경 기록 (직분·권한·셀·나눔범위) -------------------------------------------
create table if not exists audit_log (
  id            integer primary key autoincrement,
  church_id     integer not null,
  actor_id      integer references members(id),
  actor_name    text,
  action        text not null,
  target_member integer,
  target_name   text,
  detail        text,
  created_at    text not null default (datetime('now'))
);
create index if not exists audit_church on audit_log(church_id, created_at desc);

-- 건의함 --------------------------------------------------------------------
create table if not exists suggestions (
  id         integer primary key autoincrement,
  church_id  integer,
  member_id  integer,
  name       text,
  body       text not null,
  is_read    integer not null default 0,
  created_at text not null default (datetime('now'))
);

-- 이단 자동 거부 키워드 --------------------------------------------------------
create table if not exists blocked_keywords (
  keyword text primary key,
  note    text
);
insert or ignore into blocked_keywords(keyword, note) values
  ('신천지',           '이단 규정 단체'),
  ('증거장막',         '신천지 계열'),
  ('이만희',           '신천지'),
  ('하나님의교회',     '안상홍 계열'),
  ('안상홍',           '하나님의교회'),
  ('장길자',           '하나님의교회'),
  ('기독교복음선교회', 'JMS'),
  ('정명석',           'JMS'),
  ('기독교복음침례회', '구원파 계열'),
  ('구원파',           '이단 규정 단체'),
  ('박옥수',           '구원파'),
  ('유병언',           '구원파'),
  ('가정연합',         '통일교'),
  ('통일교',           '이단 규정 단체'),
  ('문선명',           '통일교'),
  ('전능신교',         '동방번개'),
  ('동방번개',         '이단 규정 단체'),
  ('여호와의증인',     '이단 규정 단체'),
  ('몰몬',             '이단 규정 단체'),
  ('말일성도',         '몰몬'),
  ('만민중앙',         '이단 규정 단체'),
  ('이재록',           '만민중앙'),
  ('은혜로교회',       '이단 규정 단체'),
  ('신옥주',           '은혜로교회');

-- ─────────────────────────────────────────────────────────────────────────
-- 한 사람이 여러 셀에 속할 수 있게 (목사·사모는 모든 셀에 걸치기도 합니다)
-- members.cell_id 는 '대표 셀'(화면 표시용)로 남겨 두고,
-- 실제 소속은 아래 표가 기준입니다.
-- ─────────────────────────────────────────────────────────────────────────
create table if not exists member_cells (
  member_id integer not null references members(id) on delete cascade,
  cell_id   integer not null references cells(id)   on delete cascade,
  primary key (member_id, cell_id)
);
create index if not exists member_cells_cell on member_cells(cell_id);

-- 기존에 한 명당 하나씩 들어 있던 셀을 옮깁니다 (여러 번 실행해도 안전)
insert or ignore into member_cells(member_id, cell_id)
  select id, cell_id from members where cell_id is not null;

-- ─────────────────────────────────────────────────────────────────────────
-- 알림
-- ─────────────────────────────────────────────────────────────────────────
create table if not exists push_tokens (
  id         integer primary key autoincrement,
  member_id  integer not null references members(id) on delete cascade,
  kind       text not null,          -- 'fcm' (안드로이드 앱) | 'web' (웹앱)
  token      text not null,          -- fcm 토큰 또는 웹 구독 endpoint
  created_at text not null default (datetime('now')),
  last_ok    text
);
create unique index if not exists push_tokens_uniq on push_tokens(kind, token);
create index if not exists push_tokens_member on push_tokens(member_id);

-- 보낸 알림 기록 (웹앱이 내용을 받아 가기 위해서도 씁니다)
create table if not exists notifications (
  id         integer primary key autoincrement,
  member_id  integer not null references members(id) on delete cascade,
  title      text not null,
  body       text not null,
  link       text,
  read_flag  integer not null default 0,
  created_at text not null default (datetime('now'))
);
create index if not exists notifications_member on notifications(member_id, created_at desc);

-- 계정 삭제 요청 (Play 스토어 요구사항 — 로그인 없이 신청할 수 있어야 합니다)
create table if not exists deletion_requests (
  id          integer primary key autoincrement,
  church_name text,
  member_name text not null,
  contact     text,
  note        text,
  status      text not null default 'pending',   -- pending / done / rejected
  created_at  text not null default (datetime('now')),
  handled_at  text
);
create index if not exists deletion_requests_new on deletion_requests(status, created_at desc);

-- ─────────────────────────────────────────────────────────────────────────
-- 묵상 나눔 사진 (2026-09 추가)
--   사진 파일은 R2 에, 여기에는 어느 글에 붙은 사진인지만 적습니다.
--   token 은 추측할 수 없는 긴 난수라 /img/<token> 주소로 바로 보여 줍니다.
--   entry_id 가 비어 있으면 '올렸지만 아직 글에 붙지 않은' 사진입니다.
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

-- ─────────────────────────────────────────────────────────────────────────
-- 묵상 공유 링크 (2026-09 추가)
--   /s/<token> 주소로 교회 밖 사람도 그 글 하나만 볼 수 있습니다.
--   글이 지워지거나 '나만 보기'로 바뀌면 링크도 더 이상 보이지 않습니다.
-- ─────────────────────────────────────────────────────────────────────────
create table if not exists entry_shares (
  entry_id   integer primary key references entries(id) on delete cascade,
  token      text not null,
  member_id  integer not null,
  created_at text not null default (datetime('now'))
);
create unique index if not exists entry_shares_token on entry_shares(token);
