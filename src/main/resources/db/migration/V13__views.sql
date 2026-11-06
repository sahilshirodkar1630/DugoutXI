-- Other people's picks. Returns nothing until the contest has started.
create view v_visible_predictions as
select cp.contest_id, cp.user_id, cp.selected_team_id, cp.predicted_at
  from contest_participants cp
  join contests c on c.id = cp.contest_id
 where now() >= c.start_time
   and cp.participant_status = 'CONFIRMED';

-- Live league board: one row per paid member.
create view v_league_member_pl as
select lm.league_id,
       lm.user_id,
       l.joining_amount                                     as joining_amount_paid,
       count(*) filter (where cp.result = 'WON')            as won_count,
       count(*) filter (where cp.result = 'LOST')           as lost_count,
       count(*) filter (where cp.result = 'REFUNDED')       as refunded_count,
       coalesce(sum(cp.winning_amount), 0)                  as winnings,
       coalesce(sum(cp.entry_spent), 0)                     as entries_spent,
       coalesce(sum(cp.winning_amount - cp.entry_spent), 0) as net_pl
  from league_members lm
  join leagues l on l.id = lm.league_id
  left join contest_participants cp
         on cp.league_member_id = lm.id and cp.result is not null
 where lm.payment_status = 'PAID'
 group by lm.id, l.joining_amount;

-- Per-user totals across leagues and standalone contests.
create view v_user_stats as
select u.id as user_id,
       (select count(*) from league_members lm
         where lm.user_id = u.id and lm.payment_status = 'PAID') as leagues_joined,
       coalesce(s.contests_played, 0)   as contests_played,
       coalesce(s.contests_won, 0)      as contests_won,
       coalesce(s.contests_lost, 0)     as contests_lost,
       coalesce(s.contests_refunded, 0) as contests_refunded,
       coalesce(s.amount_won, 0)        as amount_won,
       coalesce(s.amount_lost, 0)       as amount_lost,
       coalesce(s.net_pl, 0)            as net_pl
  from users u
  left join (
    select user_id,
           count(*) filter (where result in ('WON','LOST')) as contests_played,
           count(*) filter (where result = 'WON')           as contests_won,
           count(*) filter (where result = 'LOST')          as contests_lost,
           count(*) filter (where result = 'REFUNDED')      as contests_refunded,
           sum(winning_amount)                              as amount_won,
           sum(entry_spent) filter (where result = 'LOST')  as amount_lost,
           sum(winning_amount - entry_spent)                as net_pl
      from contest_participants
     where result is not null
     group by user_id
  ) s on s.user_id = u.id;
