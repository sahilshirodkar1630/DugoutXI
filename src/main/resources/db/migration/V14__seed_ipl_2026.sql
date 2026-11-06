-- Sample seed so the app has a tournament and teams to pick from.
-- Adjust names and dates as needed. An admin user is NOT seeded here (it needs a BCrypt hash).
insert into tournaments (name, start_date, end_date) values ('IPL 2026', null, null);

insert into teams (name, short_code) values
  ('Chennai Super Kings',        'CSK'),
  ('Mumbai Indians',             'MI'),
  ('Royal Challengers Bengaluru','RCB'),
  ('Kolkata Knight Riders',      'KKR'),
  ('Sunrisers Hyderabad',        'SRH'),
  ('Delhi Capitals',             'DC'),
  ('Punjab Kings',               'PBKS'),
  ('Rajasthan Royals',           'RR'),
  ('Gujarat Titans',             'GT'),
  ('Lucknow Super Giants',       'LSG');

insert into tournament_teams (tournament_id, team_id)
select t.id, tm.id from tournaments t cross join teams tm where t.name = 'IPL 2026';
