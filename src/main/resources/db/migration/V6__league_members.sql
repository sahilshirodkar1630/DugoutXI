create table league_members (
  id                   bigint generated always as identity primary key,
  league_id            bigint not null references leagues(id),
  user_id              bigint not null references users(id),
  league_member_status text   not null default 'PENDING_APPROVAL'
    check (league_member_status in ('PENDING_APPROVAL','ACCEPTED_UNPAID','CONFIRMED','REJECTED','REMOVED','CANCELLED')),
  payment_status       text   not null default 'PENDING'
    check (payment_status in ('PENDING','PAID')),
  paid_at              timestamptz,
  status_reason        text,
  requested_at         timestamptz not null default now(),
  confirmed_at         timestamptz,
  removed_at           timestamptz,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  version              integer     not null default 0,
  unique (league_id, user_id),
  check (league_member_status <> 'CONFIRMED' or payment_status = 'PAID'),
  check ((payment_status = 'PAID') = (paid_at is not null))
);
create index ix_lm_league_status on league_members (league_id, league_member_status);
create index ix_lm_user          on league_members (user_id);
