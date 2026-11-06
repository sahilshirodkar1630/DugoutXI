create table tournaments (
  id         bigint generated always as identity primary key,
  name       text        not null unique,
  start_date date,
  end_date   date,
  is_active  boolean     not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version    integer     not null default 0
);
