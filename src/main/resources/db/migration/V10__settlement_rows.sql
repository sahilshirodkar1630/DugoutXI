create table settlement_rows (
  id                  bigint generated always as identity primary key,
  league_id           bigint        not null references leagues(id),
  league_member_id    bigint        not null references league_members(id),
  user_id             bigint        not null references users(id),
  is_organizer        boolean       not null default false,
  joining_amount_paid numeric(12,2) not null check (joining_amount_paid >= 0),
  net_pl              numeric(12,2) not null,
  final_balance       numeric(12,2) not null check (final_balance >= 0),
  settlement_status   text          not null default 'PENDING'
    check (settlement_status in ('PENDING','SETTLED','WRITTEN_OFF')),
  settled_at          timestamptz,
  settled_by          bigint        references users(id),
  written_off_reason  text,
  created_at          timestamptz   not null default now(),
  updated_at          timestamptz   not null default now(),
  version             integer       not null default 0,

  unique (league_id, league_member_id),
  check (final_balance = joining_amount_paid + net_pl),
  check ((settlement_status = 'SETTLED') = (settled_at is not null)),
  check (settlement_status <> 'WRITTEN_OFF'
         or length(trim(coalesce(written_off_reason, ''))) > 0)
);
create index ix_settlement_pending on settlement_rows (league_id) where settlement_status = 'PENDING';
create index ix_settlement_user    on settlement_rows (user_id, settlement_status);
