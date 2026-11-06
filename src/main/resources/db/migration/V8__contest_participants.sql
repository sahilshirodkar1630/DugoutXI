create table contest_participants (
  id                 bigint generated always as identity primary key,
  contest_id         bigint not null references contests(id),
  user_id            bigint not null references users(id),
  league_member_id   bigint references league_members(id),
  participant_status text   not null default 'PENDING_APPROVAL'
    check (participant_status in ('PENDING_APPROVAL','ACCEPTED_UNPAID','CONFIRMED','REJECTED','REMOVED','CANCELLED')),
  payment_status     text   not null
    check (payment_status in ('NOT_REQUIRED','PENDING','PAID')),
  selected_team_id   bigint references teams(id),
  predicted_at       timestamptz,
  result             text   check (result in ('WON','LOST','REFUNDED')),
  entry_spent        numeric(10,2) not null default 0 check (entry_spent >= 0),
  winning_amount     numeric(10,2) not null default 0 check (winning_amount >= 0),
  status_reason      text,
  requested_at       timestamptz not null default now(),
  confirmed_at       timestamptz,
  removed_at         timestamptz,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  version            integer     not null default 0,

  unique (contest_id, user_id),
  check ((league_member_id is null) = (payment_status <> 'NOT_REQUIRED')),
  check (participant_status <> 'CONFIRMED' or payment_status in ('NOT_REQUIRED','PAID')),
  check ((selected_team_id is null) = (predicted_at is null)),
  check (
    (result is null       and entry_spent = 0 and winning_amount = 0) or
    (result = 'WON'       and entry_spent > 0 and winning_amount > 0) or
    (result = 'LOST'      and entry_spent > 0 and winning_amount = 0) or
    (result = 'REFUNDED'  and entry_spent = 0 and winning_amount = 0)
  )
);
create index ix_cp_contest_status on contest_participants (contest_id, participant_status);
create index ix_cp_user_result    on contest_participants (user_id) where result is not null;
create index ix_cp_league_member  on contest_participants (league_member_id) where league_member_id is not null;

-- Second lock behind the service's own time check: no late, invalid or unconfirmed picks.
create function fn_check_selection() returns trigger language plpgsql as $$
declare c contests%rowtype;
begin
  if new.selected_team_id is null then return new; end if;
  if tg_op = 'UPDATE' and new.selected_team_id is not distinct from old.selected_team_id then
    return new;                                   -- result updates don't re-check
  end if;
  select * into c from contests where id = new.contest_id;
  if now() >= c.start_time then
    raise exception 'contest % has started; predictions are locked', c.id;
  end if;
  if new.selected_team_id not in (c.team_a_id, c.team_b_id) then
    raise exception 'team % is not playing in contest %', new.selected_team_id, c.id;
  end if;
  if new.participant_status <> 'CONFIRMED' then
    raise exception 'only confirmed participants can predict';
  end if;
  return new;
end $$;

create trigger trg_cp_selection before insert or update on contest_participants
for each row execute function fn_check_selection();
