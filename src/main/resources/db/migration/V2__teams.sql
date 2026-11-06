create table teams (
  id         bigint generated always as identity primary key,
  name       text        not null unique,
  short_code text        not null unique,
  logo_url   text,
  is_active  boolean     not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version    integer     not null default 0
);
