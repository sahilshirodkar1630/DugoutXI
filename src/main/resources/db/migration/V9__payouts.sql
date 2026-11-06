create table payouts (
  id                     bigint generated always as identity primary key,
  contest_id             bigint        not null references contests(id),
  contest_participant_id bigint        not null references contest_participants(id),
  user_id                bigint        not null references users(id),
  payout_type            text          not null check (payout_type in ('WINNING','REFUND')),
  amount                 numeric(10,2) not null check (amount > 0),
  payout_status          text          not null default 'PENDING'
    check (payout_status in ('PENDING','PAID_OUT','VOID')),
  paid_out_at            timestamptz,
  paid_out_by            bigint        references users(id),
  created_at             timestamptz   not null default now(),
  updated_at             timestamptz   not null default now(),
  version                integer       not null default 0,
  check ((payout_status = 'PAID_OUT') = (paid_out_at is not null))
);
create unique index uq_payout_live on payouts (contest_participant_id) where payout_status <> 'VOID';
create index ix_payouts_contest on payouts (contest_id, payout_status);
create index ix_payouts_user    on payouts (user_id, payout_status);
