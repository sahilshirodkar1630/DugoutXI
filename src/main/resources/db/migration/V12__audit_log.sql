create table audit_log (
  id                 bigint generated always as identity primary key,
  entity_type        text        not null,
  entity_id          bigint      not null,
  league_id          bigint      references leagues(id),
  contest_id         bigint      references contests(id),
  action             text        not null,
  actor_id           bigint      references users(id),
  actor_type         text        not null check (actor_type in ('USER','ADMIN','SYSTEM')),
  reason             text,
  before_data        jsonb,
  after_data         jsonb,
  visible_to_members boolean     not null default false,
  created_at         timestamptz not null default now(),
  check (actor_type <> 'ADMIN' or length(trim(coalesce(reason, ''))) > 0),
  check (actor_type = 'SYSTEM' or actor_id is not null)
);
create index ix_audit_entity on audit_log (entity_type, entity_id, created_at desc);
create index ix_audit_league_members  on audit_log (league_id, created_at desc)  where visible_to_members;
create index ix_audit_contest_members on audit_log (contest_id, created_at desc) where visible_to_members;

create function fn_audit_append_only() returns trigger language plpgsql as $$
begin raise exception 'audit_log is append-only'; end $$;

create trigger trg_audit_append_only before update or delete on audit_log
for each row execute function fn_audit_append_only();
