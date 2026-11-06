package com.sahilprojects.bettingApp.dashboard;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only queries over the database views and aggregates. These are plain JDBC because they do not map to one entity.
 * Everything is calculated on request, so a result correction can never leave a stored total wrong.
 */
@Repository
public class DashboardQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Live league board, ranked by net profit/loss. */
    public List<LeagueBoardRow> leagueBoard(long leagueId) {
        String sql = """
                select v.user_id, u.display_name,
                       rank() over (order by v.net_pl desc) as rank,
                       v.won_count, v.lost_count, v.refunded_count,
                       v.winnings, v.entries_spent, v.net_pl,
                       v.joining_amount_paid,
                       v.joining_amount_paid + v.net_pl as running_balance,
                       sr.settlement_status
                  from v_league_member_pl v
                  join users u on u.id = v.user_id
                  left join settlement_rows sr
                         on sr.league_id = v.league_id and sr.user_id = v.user_id
                 where v.league_id = :leagueId
                 order by rank, u.display_name
                """;
        return jdbc.query(sql, new MapSqlParameterSource("leagueId", leagueId), (rs, i) -> new LeagueBoardRow(
                rs.getLong("user_id"), rs.getString("display_name"), rs.getInt("rank"),
                rs.getInt("won_count"), rs.getInt("lost_count"), rs.getInt("refunded_count"),
                rs.getBigDecimal("winnings"), rs.getBigDecimal("entries_spent"),
                rs.getBigDecimal("net_pl"), rs.getBigDecimal("joining_amount_paid"),
                rs.getBigDecimal("running_balance"), rs.getString("settlement_status")));
    }

    /** Totals for one user across leagues and standalone contests. */
    public Optional<UserStatsRow> userStats(long userId) {
        String sql = "select * from v_user_stats where user_id = :userId";
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, i) -> new UserStatsRow(
                rs.getLong("user_id"), rs.getInt("leagues_joined"), rs.getInt("contests_played"),
                rs.getInt("contests_won"), rs.getInt("contests_lost"), rs.getInt("contests_refunded"),
                rs.getBigDecimal("amount_won"), rs.getBigDecimal("amount_lost"), rs.getBigDecimal("net_pl")))
                .stream().findFirst();
    }

    /**
     * Every contest the user has a place in that counts: decided or refunded ones, and confirmed ones still waiting
     * for a result. Contests starting before `from` are left out. Oldest first.
     */
    public List<MyResultRow> myResults(long userId, Instant from) {
        String sql = """
                select c.id, c.start_time, c.league_id, l.name as league_name, c.contest_number, c.name,
                       ta.short_code as team_a, tb.short_code as team_b, c.contest_status,
                       w.short_code as winner, p.short_code as picked,
                       cp.result, cp.entry_spent, cp.winning_amount
                  from contest_participants cp
                  join contests c   on c.id = cp.contest_id
                  join teams ta     on ta.id = c.team_a_id
                  join teams tb     on tb.id = c.team_b_id
                  left join teams w on w.id = c.winning_team_id
                  left join teams p on p.id = cp.selected_team_id
                  left join leagues l on l.id = c.league_id
                 where cp.user_id = :userId
                   and c.start_time >= :from
                   and (cp.result is not null
                        or (cp.participant_status = 'CONFIRMED' and c.contest_status <> 'CANCELLED'))
                 order by c.start_time, c.id
                """;
        var params = new MapSqlParameterSource("userId", userId)
                .addValue("from", Timestamp.from(from == null ? Instant.EPOCH : from));
        return jdbc.query(sql, params, (rs, i) -> new MyResultRow(
                rs.getLong("id"), rs.getTimestamp("start_time").toInstant(),
                (Long) rs.getObject("league_id", Long.class), rs.getString("league_name"),
                (Integer) rs.getObject("contest_number", Integer.class), rs.getString("name"),
                rs.getString("team_a"), rs.getString("team_b"), rs.getString("contest_status"),
                rs.getString("winner"), rs.getString("picked"), rs.getString("result"),
                rs.getBigDecimal("entry_spent"), rs.getBigDecimal("winning_amount")));
    }

    /** Other people's picks. Returns nothing until the contest has started, because the view enforces it. */
    public List<VisiblePredictionRow> visiblePredictions(long contestId) {
        String sql = """
                select p.user_id, u.display_name, p.selected_team_id
                  from v_visible_predictions p
                  join users u on u.id = p.user_id
                 where p.contest_id = :contestId and p.selected_team_id is not null
                 order by u.display_name
                """;
        return jdbc.query(sql, new MapSqlParameterSource("contestId", contestId), (rs, i) -> new VisiblePredictionRow(
                rs.getLong("user_id"), rs.getString("display_name"), rs.getLong("selected_team_id")));
    }

    /** One row per paid league member, used to create the settlement rows when a league ends. */
    public List<SettlementCalcRow> settlementCalc(long leagueId) {
        String sql = """
                select lm.id as league_member_id, lm.user_id,
                       l.joining_amount as joining_amount_paid,
                       coalesce(sum(cp.winning_amount - cp.entry_spent), 0) as net_pl
                  from league_members lm
                  join leagues l on l.id = lm.league_id
                  left join contest_participants cp
                         on cp.league_member_id = lm.id and cp.result is not null
                 where lm.league_id = :leagueId and lm.payment_status = 'PAID'
                 group by lm.id, lm.user_id, l.joining_amount
                """;
        return jdbc.query(sql, new MapSqlParameterSource("leagueId", leagueId), (rs, i) -> new SettlementCalcRow(
                rs.getLong("league_member_id"), rs.getLong("user_id"),
                rs.getBigDecimal("joining_amount_paid"), rs.getBigDecimal("net_pl")));
    }

    /**
     * Add-up check before completing a league: total final balances must equal total joining amounts
     * minus the leftover paise saved on the league's contests. False means a calculation bug.
     */
    public boolean settlementBalances(long leagueId) {
        String sql = """
                select (select coalesce(sum(final_balance), 0) from settlement_rows where league_id = :leagueId)
                     = (select coalesce(sum(joining_amount_paid), 0) from settlement_rows where league_id = :leagueId)
                     - (select coalesce(sum(leftover_amount), 0) from contests
                         where league_id = :leagueId and contest_status in ('RESULT_DECLARED','SETTLED'))
                """;
        Boolean ok = jdbc.queryForObject(sql, new MapSqlParameterSource("leagueId", leagueId), Boolean.class);
        return Boolean.TRUE.equals(ok);
    }
}
