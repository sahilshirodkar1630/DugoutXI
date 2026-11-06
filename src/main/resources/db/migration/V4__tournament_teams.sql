create table tournament_teams (
  tournament_id bigint not null references tournaments(id),
  team_id       bigint not null references teams(id),
  primary key (tournament_id, team_id)
);
create index ix_tt_team on tournament_teams (team_id);
