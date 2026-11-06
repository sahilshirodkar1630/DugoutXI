create table notifications (
  id                bigint generated always as identity primary key,
  user_id           bigint      not null references users(id),
  notification_type text        not null,
  reference_type    text        check (reference_type in ('LEAGUE','CONTEST')),
  reference_id      bigint,
  title             text        not null,
  message           text,
  scheduled_for     timestamptz not null,
  sent_at           timestamptz,
  read_at           timestamptz,
  dedupe_key        text        not null unique,
  created_at        timestamptz not null default now(),
  check ((reference_type is null) = (reference_id is null)),
  check (read_at is null or sent_at is not null)
);
create index ix_notifications_due    on notifications (scheduled_for) where sent_at is null;
create index ix_notifications_user   on notifications (user_id, sent_at desc) where sent_at is not null;
create index ix_notifications_unread on notifications (user_id) where sent_at is not null and read_at is null;
