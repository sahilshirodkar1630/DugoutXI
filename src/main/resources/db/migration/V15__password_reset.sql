-- Password reset without email: an admin sets a temporary password, and the user must pick their own at the next
-- sign-in. Changing a password also signs out every older session: a token issued before password_changed_at is
-- refused (JwtAuthenticationFilter).
alter table users
  add column must_change_password     boolean     not null default false,
  add column password_changed_at      timestamptz,
  -- "Forgot password?" alerts the admins; this keeps one person from flooding them with repeats.
  add column password_reset_requested_at timestamptz;
