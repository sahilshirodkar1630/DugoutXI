create table leagues (
  id                    bigint generated always as identity primary key,
  organizer_id          bigint        not null references users(id),
  tournament_id         bigint        not null references tournaments(id),
  name                  text          not null,
  amount_per_contest    numeric(10,2) not null check (amount_per_contest > 0),
  total_contests        integer       not null check (total_contests > 0),
  joining_amount        numeric(12,2) generated always as (amount_per_contest * total_contests) stored,
  registration_start    timestamptz   not null,
  registration_deadline timestamptz   not null,
  payment_deadline      timestamptz   not null,
  payment_instructions  text,
  invite_code           text          not null unique,
  status                text          not null default 'UPCOMING'
    check (status in ('UPCOMING','OPEN','ACTIVE','PENDING_SETTLEMENT','COMPLETED','CANCELLED')),
  started_at            timestamptz,
  ended_at              timestamptz,
  completed_at          timestamptz,
  force_closed_by       bigint references users(id),
  force_close_reason    text,
  created_at            timestamptz   not null default now(),
  updated_at            timestamptz   not null default now(),
  version               integer       not null default 0,
  check (registration_start <= registration_deadline),
  check (registration_deadline <= payment_deadline),
  unique (id, tournament_id)
);
create index ix_leagues_organizer on leagues (organizer_id);
create index ix_leagues_status    on leagues (status);
