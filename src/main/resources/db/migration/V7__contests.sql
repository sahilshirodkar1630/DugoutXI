create table contests (
  id                    bigint generated always as identity primary key,
  league_id             bigint        references leagues(id),
  organizer_id          bigint        not null references users(id),
  tournament_id         bigint        not null references tournaments(id),
  name                  text,
  contest_number        integer       check (contest_number > 0),
  team_a_id             bigint        not null,
  team_b_id             bigint        not null,
  start_time            timestamptz   not null,
  contest_amount        numeric(10,2) not null check (contest_amount > 0),
  registration_deadline timestamptz,
  payment_deadline      timestamptz,
  payment_instructions  text,
  contest_status        text          not null default 'SCHEDULED'
    check (contest_status in ('SCHEDULED','LOCKED','RESULT_DECLARED','SETTLED','CANCELLED')),
  result_type           text          check (result_type in ('WINNER','NO_RESULT')),
  winning_team_id       bigint        references teams(id),
  result_source         text          check (result_source in ('ORGANIZER','API')),
  result_entered_at     timestamptz,
  result_entered_by     bigint        references users(id),
  leftover_amount       numeric(10,2) not null default 0 check (leftover_amount >= 0),
  locked_at             timestamptz,
  cancelled_reason      text,
  created_at            timestamptz   not null default now(),
  updated_at            timestamptz   not null default now(),
  version               integer       not null default 0,

  foreign key (tournament_id, team_a_id) references tournament_teams (tournament_id, team_id),
  foreign key (tournament_id, team_b_id) references tournament_teams (tournament_id, team_id),
  foreign key (league_id, tournament_id) references leagues (id, tournament_id),

  check (team_a_id <> team_b_id),
  check (contest_number is null or league_id is not null),
  check (winning_team_id is null or winning_team_id in (team_a_id, team_b_id)),
  check (
    (result_type is null       and winning_team_id is null) or
    (result_type = 'WINNER'    and winning_team_id is not null) or
    (result_type = 'NO_RESULT' and winning_team_id is null)
  ),
  check ((contest_status in ('RESULT_DECLARED','SETTLED')) = (result_type is not null)),
  check (league_id is not null or (
    registration_deadline is not null and payment_deadline is not null
    and registration_deadline <= payment_deadline and payment_deadline <= start_time))
);
create index ix_contests_league_start on contests (league_id, start_time);
create index ix_contests_due          on contests (start_time) where contest_status = 'SCHEDULED';
create index ix_contests_tournament   on contests (tournament_id);
create unique index uq_contests_league_number
  on contests (league_id, contest_number)
  where contest_number is not null and contest_status <> 'CANCELLED';
