-- ============================================================
--  묵상 나눔광장 — 데이터베이스 전체 설정
--
--  Supabase 왼쪽 메뉴 [SQL Editor] 에 이 파일을 통째로 붙여넣고 Run.
--  몇 번을 다시 돌려도 안전합니다 (기존 자료는 지워지지 않습니다).
-- ============================================================


-- ────────────────────────────────────────────────────────────
--  1. 표
-- ────────────────────────────────────────────────────────────

create table if not exists members (
  hex        text primary key,       -- 이름을 기계가 읽는 형태로 바꾼 값
  name       text not null,
  pin_hash   text not null,          -- 비밀번호를 뒤섞은 값 (원본은 저장 안 함)
  created_at timestamptz default now()
);

create table if not exists entries (
  id         bigserial primary key,
  hex        text not null references members(hex) on delete cascade,
  name       text not null,
  date       date not null,
  title      text not null,
  body       text not null,
  is_public  boolean not null default true,
  created_at timestamptz default now(),
  updated_at timestamptz default now(),
  unique (hex, date)
);

create index if not exists entries_hex_date on entries (hex, date desc);
create index if not exists entries_date      on entries (date desc);

-- 좋아요: 한 사람이 한 글에 한 번만
create table if not exists likes (
  hex        text not null references members(hex) on delete cascade,
  entry_hex  text not null,
  entry_date date not null,
  created_at timestamptz default now(),
  primary key (hex, entry_hex, entry_date),
  foreign key (entry_hex, entry_date) references entries(hex, date) on delete cascade
);

-- 댓글
create table if not exists comments (
  id         bigserial primary key,
  entry_hex  text not null,
  entry_date date not null,
  hex        text not null references members(hex) on delete cascade,
  name       text not null,
  body       text not null,
  created_at timestamptz default now(),
  foreign key (entry_hex, entry_date) references entries(hex, date) on delete cascade
);

create index if not exists comments_entry on comments (entry_hex, entry_date, created_at);

-- 관리자 표시 (건의함을 볼 수 있는 사람)
alter table members add column if not exists is_admin boolean not null default false;

-- 앱 업데이트 건의함
create table if not exists suggestions (
  id         bigserial primary key,
  hex        text,                       -- 보낸 사람 (이름을 안 정했으면 비어 있음)
  name       text not null default '익명',
  body       text not null,
  is_read    boolean not null default false,
  created_at timestamptz default now()
);

create index if not exists suggestions_new on suggestions (created_at desc);


-- ────────────────────────────────────────────────────────────
--  2. 읽기 창구 (표를 직접 열지 않고 이것만 봅니다)
-- ────────────────────────────────────────────────────────────

drop view if exists comments_public;
drop view if exists entries_public;

create view entries_public as
  select hex, name, date, title, body, created_at, updated_at
  from entries
  where is_public = true;

create view comments_public as
  select c.id, c.entry_hex, c.entry_date, c.hex, c.name, c.body, c.created_at
  from comments c
  join entries e on e.hex = c.entry_hex and e.date = c.entry_date
  where e.is_public = true;

alter view entries_public  set (security_invoker = off);
alter view comments_public set (security_invoker = off);


-- ────────────────────────────────────────────────────────────
--  3. 예전 함수 정리
--     Postgres 는 이미 있는 함수의 반환 형태를 바꾸지 못하게 막습니다.
--     그래서 먼저 지우고 아래에서 새로 만듭니다.
--     함수는 계산 절차일 뿐이라, 지워도 저장된 자료는 그대로입니다.
-- ────────────────────────────────────────────────────────────

drop function if exists claim_name(text, text, text);
drop function if exists save_entry(text, text, date, text, text, boolean, date);
drop function if exists delete_entry(text, text, date);
drop function if exists my_entries(text, text);
drop function if exists their_entries(text, text);
drop function if exists feed(date, text);
drop function if exists toggle_like(text, text, text, date);
drop function if exists add_comment(text, text, text, date, text);
drop function if exists delete_comment(text, text, bigint);
drop function if exists plaza();
drop function if exists my_progress(text, text);
drop function if exists add_suggestion(text, text, text);
drop function if exists am_i_admin(text, text);
drop function if exists list_suggestions(text, text);
drop function if exists set_suggestion_read(text, text, bigint, boolean);
drop function if exists delete_suggestion(text, text, bigint);


-- ────────────────────────────────────────────────────────────
--  4. 이름 만들기 / 비밀번호 확인
-- ────────────────────────────────────────────────────────────

create or replace function claim_name(p_hex text, p_name text, p_pin_hash text)
returns text
language plpgsql security definer set search_path = public as $$
declare v_hash text;
begin
  select pin_hash into v_hash from members where hex = p_hex;

  if v_hash is null then
    insert into members(hex, name, pin_hash) values (p_hex, left(p_name, 40), p_pin_hash);
    return 'created';
  elsif v_hash = p_pin_hash then
    return 'ok';
  else
    return 'wrong_pin';
  end if;
end $$;


-- ────────────────────────────────────────────────────────────
--  5. 글 저장 · 삭제 · 내 글 보기
-- ────────────────────────────────────────────────────────────

create or replace function save_entry(
  p_hex       text,
  p_pin_hash  text,
  p_date      date,
  p_title     text,
  p_body      text,
  p_is_public boolean,
  p_old_date  date default null)
returns text
language plpgsql security definer set search_path = public as $$
declare v_name text;
begin
  select name into v_name from members where hex = p_hex and pin_hash = p_pin_hash;
  if v_name is null then return 'wrong_pin'; end if;

  if p_old_date is not null and p_old_date <> p_date then
    delete from entries where hex = p_hex and date = p_old_date;
  end if;

  insert into entries(hex, name, date, title, body, is_public)
  values (p_hex, v_name, p_date, left(p_title, 120), left(p_body, 3000), p_is_public)
  on conflict (hex, date) do update
    set title      = excluded.title,
        body       = excluded.body,
        is_public  = excluded.is_public,
        updated_at = now();

  return 'ok';
end $$;


create or replace function delete_entry(p_hex text, p_pin_hash text, p_date date)
returns text
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from members where hex = p_hex and pin_hash = p_pin_hash) then
    return 'wrong_pin';
  end if;
  delete from entries where hex = p_hex and date = p_date;   -- 좋아요·댓글도 함께 정리됩니다
  return 'ok';
end $$;


create or replace function my_entries(p_hex text, p_pin_hash text)
returns table(date date, title text, body text, is_public boolean,
              like_count bigint, comment_count bigint, i_liked boolean)
language plpgsql security definer set search_path = public as $$
#variable_conflict use_column
begin
  if not exists (select 1 from members where hex = p_hex and pin_hash = p_pin_hash) then
    return;
  end if;
  return query
    select e.date, e.title, e.body, e.is_public,
      (select count(*) from likes    l where l.entry_hex = e.hex and l.entry_date = e.date),
      (select count(*) from comments c where c.entry_hex = e.hex and c.entry_date = e.date),
      exists (select 1 from likes l
              where l.entry_hex = e.hex and l.entry_date = e.date and l.hex = p_hex)
    from entries e
    where e.hex = p_hex
    order by e.date desc;
end $$;


-- 남의 묵상기록 — 공개된 글만. 좋아요·댓글 수가 함께 따라옵니다.
create or replace function their_entries(p_hex text, p_viewer_hex text default null)
returns table(date date, title text, body text, is_public boolean,
              like_count bigint, comment_count bigint, i_liked boolean)
language sql stable security definer set search_path = public as $$
  select e.date, e.title, e.body, true,
    (select count(*) from likes    l where l.entry_hex = e.hex and l.entry_date = e.date),
    (select count(*) from comments c where c.entry_hex = e.hex and c.entry_date = e.date),
    (p_viewer_hex is not null and exists (
       select 1 from likes l
       where l.entry_hex = e.hex and l.entry_date = e.date and l.hex = p_viewer_hex))
  from entries e
  where e.hex = p_hex and e.is_public = true
  order by e.date desc;
$$;


-- ────────────────────────────────────────────────────────────
--  6. 오늘의 나눔 (나눔광장 첫 화면)
-- ────────────────────────────────────────────────────────────

create or replace function feed(p_date date, p_hex text default null)
returns table(hex text, name text, date date, title text, body text,
              like_count bigint, comment_count bigint, i_liked boolean)
language sql stable security definer set search_path = public as $$
  select
    e.hex, e.name, e.date, e.title, e.body,
    (select count(*) from likes    l where l.entry_hex = e.hex and l.entry_date = e.date),
    (select count(*) from comments c where c.entry_hex = e.hex and c.entry_date = e.date),
    (p_hex is not null and exists (
       select 1 from likes l
       where l.entry_hex = e.hex and l.entry_date = e.date and l.hex = p_hex))
  from entries e
  where e.is_public = true and e.date = p_date
  order by e.name;
$$;


-- ────────────────────────────────────────────────────────────
--  7. 좋아요 · 댓글
-- ────────────────────────────────────────────────────────────

create or replace function toggle_like(
  p_hex text, p_pin_hash text, p_entry_hex text, p_entry_date date)
returns table(n_likes bigint, is_liked boolean)
language plpgsql security definer set search_path = public as $$
declare v_had boolean;
begin
  if not exists (select 1 from members where hex = p_hex and pin_hash = p_pin_hash) then
    return query select 0::bigint, false;
    return;
  end if;

  select exists(select 1 from likes l
                where l.hex = p_hex and l.entry_hex = p_entry_hex and l.entry_date = p_entry_date)
    into v_had;

  if v_had then
    delete from likes l
     where l.hex = p_hex and l.entry_hex = p_entry_hex and l.entry_date = p_entry_date;
  else
    insert into likes(hex, entry_hex, entry_date)
    values (p_hex, p_entry_hex, p_entry_date)
    on conflict do nothing;
  end if;

  return query
    select (select count(*) from likes l
            where l.entry_hex = p_entry_hex and l.entry_date = p_entry_date),
           not v_had;
end $$;


create or replace function add_comment(
  p_hex text, p_pin_hash text, p_entry_hex text, p_entry_date date, p_body text)
returns text
language plpgsql security definer set search_path = public as $$
declare v_name text;
begin
  select name into v_name from members where hex = p_hex and pin_hash = p_pin_hash;
  if v_name is null then return 'wrong_pin'; end if;
  if length(btrim(coalesce(p_body,''))) = 0 then return 'empty'; end if;

  insert into comments(entry_hex, entry_date, hex, name, body)
  values (p_entry_hex, p_entry_date, p_hex, v_name, left(btrim(p_body), 500));
  return 'ok';
end $$;


create or replace function delete_comment(p_hex text, p_pin_hash text, p_id bigint)
returns text
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from members where hex = p_hex and pin_hash = p_pin_hash) then
    return 'wrong_pin';
  end if;
  delete from comments c where c.id = p_id and c.hex = p_hex;   -- 자기 댓글만
  return 'ok';
end $$;


-- ────────────────────────────────────────────────────────────
--  8. 묵상 출석표 (이번 분기 잔디 + 횟수)
-- ────────────────────────────────────────────────────────────

create or replace function plaza()
returns table(hex text, name text, total bigint, quarter_count bigint, days date[])
language sql stable security definer set search_path = public as $$
  with win as (
    select date_trunc('quarter', ((now() at time zone 'Asia/Seoul')::date)::timestamp)::date as d0
  )
  select
    m.hex,
    m.name,
    count(e.date) filter (where e.is_public),
    count(e.date) filter (where e.is_public and e.date >= (select d0 from win)),
    coalesce(
      array_agg(e.date order by e.date)
        filter (where e.is_public and e.date >= (select d0 from win)),
      '{}')
  from members m
  left join entries e on e.hex = m.hex
  group by m.hex, m.name
  order by m.name;
$$;


-- ────────────────────────────────────────────────────────────
--  9. 나의 묵상 진도 (본인만 볼 수 있음)
--     ‘나만 보기’로 적은 글도 포함해서 셉니다.
--     아직 오지 않은 날은 분모에서 뺍니다.
-- ────────────────────────────────────────────────────────────

create or replace function my_progress(p_hex text, p_pin_hash text)
returns table(kind text, label text, done bigint, total bigint)
language plpgsql security definer set search_path = public as $$
declare
  v_today date := (now() at time zone 'Asia/Seoul')::date;
  v_year  int  := extract(year from v_today)::int;
  v_q     int;
  v_s     date;
  v_e     date;
begin
  if not exists (select 1 from members where hex = p_hex and pin_hash = p_pin_hash) then
    return;
  end if;

  for v_q in 1..4 loop
    v_s := make_date(v_year, (v_q - 1) * 3 + 1, 1);
    exit when v_s > v_today;
    v_e := least((v_s + interval '3 month' - interval '1 day')::date, v_today);

    return query
      select 'quarter'::text,
             v_q || '분기 (' || ((v_q-1)*3+1) || '~' || ((v_q-1)*3+3) || '월)',
             (select count(*) from entries e where e.hex = p_hex and e.date between v_s and v_e),
             (v_e - v_s + 1)::bigint;
  end loop;

  return query
    select 'year'::text,
           v_year || '년 전체',
           (select count(*) from entries e
             where e.hex = p_hex and e.date between make_date(v_year,1,1) and v_today),
           (v_today - make_date(v_year,1,1) + 1)::bigint;
end $$;


-- ────────────────────────────────────────────────────────────
-- 10. 앱 업데이트 건의함
--     보내는 건 누구나, 읽는 건 관리자만.
-- ────────────────────────────────────────────────────────────

-- 건의 보내기. 이름과 비밀번호가 맞으면 그 이름으로, 아니면 '익명'으로 남습니다.
create or replace function add_suggestion(p_hex text, p_pin_hash text, p_body text)
returns text
language plpgsql security definer set search_path = public as $$
declare v_name text;
begin
  if length(btrim(coalesce(p_body,''))) = 0 then return 'empty'; end if;

  select name into v_name from members where hex = p_hex and pin_hash = p_pin_hash;

  insert into suggestions(hex, name, body)
  values (case when v_name is null then null else p_hex end,
          coalesce(v_name, '익명'),
          left(btrim(p_body), 1000));
  return 'ok';
end $$;


-- 내가 관리자인가?
create or replace function am_i_admin(p_hex text, p_pin_hash text)
returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select m.is_admin from members m
                   where m.hex = p_hex and m.pin_hash = p_pin_hash), false);
$$;


-- 건의 목록 — 관리자가 아니면 아무것도 돌려주지 않습니다.
create or replace function list_suggestions(p_hex text, p_pin_hash text)
returns table(id bigint, name text, body text, is_read boolean, created_at timestamptz)
language plpgsql security definer set search_path = public as $$
#variable_conflict use_column
begin
  if not exists (select 1 from members m
                 where m.hex = p_hex and m.pin_hash = p_pin_hash and m.is_admin) then
    return;
  end if;
  return query
    select s.id, s.name, s.body, s.is_read, s.created_at
    from suggestions s order by s.created_at desc;
end $$;


create or replace function set_suggestion_read(
  p_hex text, p_pin_hash text, p_id bigint, p_read boolean)
returns text
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from members m
                 where m.hex = p_hex and m.pin_hash = p_pin_hash and m.is_admin) then
    return 'not_admin';
  end if;
  update suggestions s set is_read = p_read where s.id = p_id;
  return 'ok';
end $$;


create or replace function delete_suggestion(p_hex text, p_pin_hash text, p_id bigint)
returns text
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from members m
                 where m.hex = p_hex and m.pin_hash = p_pin_hash and m.is_admin) then
    return 'not_admin';
  end if;
  delete from suggestions s where s.id = p_id;
  return 'ok';
end $$;


-- ────────────────────────────────────────────────────────────
-- 11. 문 잠그기
--     표에는 아무도 직접 손대지 못하고, 위 함수와 창구로만 오갑니다.
-- ────────────────────────────────────────────────────────────

alter table members     enable row level security;
alter table entries     enable row level security;
alter table likes       enable row level security;
alter table comments    enable row level security;
alter table suggestions enable row level security;

revoke all on table members, entries, likes, comments, suggestions from anon, authenticated;

grant select on entries_public, comments_public to anon;

grant execute on function claim_name(text, text, text)                            to anon;
grant execute on function save_entry(text, text, date, text, text, boolean, date) to anon;
grant execute on function delete_entry(text, text, date)                          to anon;
grant execute on function my_entries(text, text)                                  to anon;
grant execute on function their_entries(text, text)                               to anon;
grant execute on function feed(date, text)                                        to anon;
grant execute on function toggle_like(text, text, text, date)                     to anon;
grant execute on function add_comment(text, text, text, date, text)               to anon;
grant execute on function delete_comment(text, text, bigint)                      to anon;
grant execute on function plaza()                                                 to anon;
grant execute on function my_progress(text, text)                                 to anon;
grant execute on function add_suggestion(text, text, text)                        to anon;
grant execute on function am_i_admin(text, text)                                  to anon;
grant execute on function list_suggestions(text, text)                            to anon;
grant execute on function set_suggestion_read(text, text, bigint, boolean)        to anon;
grant execute on function delete_suggestion(text, text, bigint)                   to anon;


-- ────────────────────────────────────────────────────────────
-- 12. 관리자 지정
--     앱에서 본인 이름을 먼저 만든 뒤,
--     아래 줄 맨 앞의 -- 를 지우고 이름을 바꿔서 한 번만 실행하세요.
-- ────────────────────────────────────────────────────────────

-- update members set is_admin = true where name = '이은제';
