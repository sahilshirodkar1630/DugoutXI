create table users (
  id            bigint generated always as identity primary key,
  name          text        not null,
  display_name  text        not null,
  email         text        not null,
  password_hash text        not null,
  phone_number  text,
  photo_url     text,
  upi_id        text,
  role          text        not null default 'USER' check (role in ('USER','ADMIN')),
  is_active     boolean     not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  version       integer     not null default 0
);
create unique index uq_users_email on users (lower(email));
