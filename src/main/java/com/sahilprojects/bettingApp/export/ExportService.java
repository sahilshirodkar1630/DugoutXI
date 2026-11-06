package com.sahilprojects.bettingApp.export;

import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.dashboard.DashboardQueryRepository;
import com.sahilprojects.bettingApp.dashboard.LeagueBoardRow;
import com.sahilprojects.bettingApp.dashboard.MyResultRow;
import com.sahilprojects.bettingApp.dashboard.PeriodStats;
import com.sahilprojects.bettingApp.dashboard.StatsRange;
import com.sahilprojects.bettingApp.dashboard.dto.PeriodStatsResponse;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.league.LeagueRepository;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Excel copies of the records, so players and organizers have their own copy of who paid, who picked what and who
 * is owed what, whatever happens to the app.
 *
 * A league workbook can be downloaded by anyone who can see the league table. It holds only what members can already
 * see in the app: picks appear for contests that have started, never before.
 */
@Service
public class ExportService {

    private final LeagueRepository leagues;
    private final UserRepository users;
    private final DashboardQueryRepository dashboard;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessGuard accessGuard;
    private final Clock clock;

    public ExportService(LeagueRepository leagues, UserRepository users, DashboardQueryRepository dashboard,
                         NamedParameterJdbcTemplate jdbc, AccessGuard accessGuard, Clock clock) {
        this.leagues = leagues;
        this.users = users;
        this.dashboard = dashboard;
        this.jdbc = jdbc;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    public record Export(String fileName, byte[] content) {
    }

    // ---------- a league's season ----------

    @Transactional(readOnly = true)
    public Export league(AuthenticatedUser actor, Long leagueId) {
        League league = leagues.findById(leagueId).orElseThrow(() -> new NotFoundException("League not found"));
        accessGuard.requireLeagueMember(actor, league);
        Instant now = clock.instant();
        var params = new MapSqlParameterSource("leagueId", leagueId);

        try (Sheets book = new Sheets()) {
            Sheets.Table about = book.summary("League", league.getName());
            about.row("Tournament", league.getTournament().getName())
                    .row("Organizer", league.getOrganizer().getDisplayName())
                    .row("Status", label(league.getStatus().name()))
                    .row("Per match", league.getAmountPerContest())
                    .row("Matches", league.getTotalContests())
                    .row("Season buy-in", league.getJoiningAmount())
                    .row("Joining closed", league.getRegistrationDeadline())
                    .row("Payment due", league.getPaymentDeadline())
                    .row("How to pay", league.getPaymentInstructions())
                    .blank()
                    .row("Exported", now)
                    .row("Exported by", users.findById(actor.id()).map(User::getDisplayName).orElse(""));
            about.fit();

            Sheets.Table table = book.table("Table", "Rank", "Player", "Won", "Lost", "Refunded", "Entries spent",
                    "Winnings", "Net profit/loss", "Paid in", "Balance to hand back", "Settled");
            for (LeagueBoardRow r : dashboard.leagueBoard(leagueId)) {
                table.row(r.rank(), r.displayName(), r.wonCount(), r.lostCount(), r.refundedCount(), r.entriesSpent(),
                        r.winnings(), r.netPl(), r.joiningAmountPaid(), r.runningBalance(),
                        r.settlementStatus() == null ? null : label(r.settlementStatus()));
            }
            table.fit();

            Sheets.Table matches = book.table("Matches", "Match", "Starts", "Team A", "Team B", "Name", "Status",
                    "Winner", "Result", "Leftover (paise kept)");
            jdbc.query("""
                    select c.contest_number, c.start_time, ta.short_code a, tb.short_code b, c.name, c.contest_status,
                           w.short_code winner, c.result_type, c.leftover_amount
                      from contests c
                      join teams ta on ta.id = c.team_a_id
                      join teams tb on tb.id = c.team_b_id
                      left join teams w on w.id = c.winning_team_id
                     where c.league_id = :leagueId
                     order by c.start_time, c.contest_number
                    """, params, (ResultSet rs) -> {
                matches.row(rs.getInt("contest_number"), time(rs, "start_time"), rs.getString("a"), rs.getString("b"),
                        rs.getString("name"), label(rs.getString("contest_status")), rs.getString("winner"),
                        label(rs.getString("result_type")), rs.getBigDecimal("leftover_amount"));
            });
            matches.fit();

            Sheets.Table picks = book.table("Picks", "Match", "Starts", "Player", "Picked", "Winner", "Result",
                    "Spent", "Won", "Net");
            jdbc.query("""
                    select c.contest_number, c.start_time, u.display_name, p.short_code picked, w.short_code winner,
                           cp.result, cp.entry_spent, cp.winning_amount
                      from contest_participants cp
                      join contests c on c.id = cp.contest_id
                      join users u on u.id = cp.user_id
                      left join teams p on p.id = cp.selected_team_id
                      left join teams w on w.id = c.winning_team_id
                     where c.league_id = :leagueId
                       and c.contest_status in ('LOCKED', 'RESULT_DECLARED', 'SETTLED')
                       and cp.participant_status = 'CONFIRMED'
                     order by c.start_time, c.contest_number, u.display_name
                    """, params, (ResultSet rs) -> {
                BigDecimal spent = rs.getBigDecimal("entry_spent");
                BigDecimal won = rs.getBigDecimal("winning_amount");
                picks.row(rs.getInt("contest_number"), time(rs, "start_time"), rs.getString("display_name"),
                        orDash(rs.getString("picked")), rs.getString("winner"), label(rs.getString("result")),
                        spent, won, rs.getString("result") == null ? null : won.subtract(spent));
            });
            picks.fit();

            Sheets.Table members = book.table("Members", "Player", "Status", "Payment", "Paid on", "Asked to join");
            jdbc.query("""
                    select u.display_name, lm.league_member_status, lm.payment_status, lm.paid_at, lm.requested_at
                      from league_members lm
                      join users u on u.id = lm.user_id
                     where lm.league_id = :leagueId
                     order by lm.league_member_status, u.display_name
                    """, params, (ResultSet rs) -> {
                members.row(rs.getString("display_name"), label(rs.getString("league_member_status")),
                        label(rs.getString("payment_status")), time(rs, "paid_at"), time(rs, "requested_at"));
            });
            members.fit();

            Sheets.Table settlement = book.table("Settle up", "Player", "Organizer", "Paid in", "Net profit/loss",
                    "Hand back", "Status", "Settled on");
            jdbc.query("""
                    select u.display_name, sr.is_organizer, sr.joining_amount_paid, sr.net_pl, sr.final_balance,
                           sr.settlement_status, sr.settled_at
                      from settlement_rows sr
                      join users u on u.id = sr.user_id
                     where sr.league_id = :leagueId
                     order by sr.final_balance desc, u.display_name
                    """, params, (ResultSet rs) -> {
                settlement.row(rs.getString("display_name"), rs.getBoolean("is_organizer"),
                        rs.getBigDecimal("joining_amount_paid"), rs.getBigDecimal("net_pl"),
                        rs.getBigDecimal("final_balance"), label(rs.getString("settlement_status")),
                        time(rs, "settled_at"));
            });
            settlement.fit();

            return new Export(fileName(league.getName() + " season", now), book.toBytes());
        }
    }

    // ---------- my own history ----------

    @Transactional(readOnly = true)
    public Export mine(AuthenticatedUser actor) {
        User me = users.findById(actor.id()).orElseThrow(() -> new NotFoundException("User not found"));
        Instant now = clock.instant();
        List<MyResultRow> rows = dashboard.myResults(actor.id(), null);
        PeriodStatsResponse all = PeriodStats.compute(StatsRange.ALL, null, now, rows);
        var params = new MapSqlParameterSource("userId", actor.id());

        try (Sheets book = new Sheets()) {
            Sheets.Table summary = book.summary("Summary", me.getDisplayName() + " — all-time record");
            summary.row("Contests played", all.contestsPlayed())
                    .row("Won", all.contestsWon())
                    .row("Lost", all.contestsLost())
                    .row("Refunded", all.contestsRefunded())
                    .row("Waiting for a result", all.contestsPending())
                    .row("Win rate %", all.winPercentage())
                    .row("Money put in", all.amountInvested())
                    .row("Money won", all.amountWon())
                    .row("Net profit/loss", all.netPl())
                    .row("Return %", all.returnPercentage())
                    .row("Biggest win", all.biggestWin())
                    .row("Best winning streak", all.bestStreak())
                    .row("Leagues played", all.leaguesPlayed())
                    .blank()
                    .row("Exported", now);
            summary.fit();

            Sheets.Table months = book.table("By month", "Month", "Played", "Won", "Put in", "Net profit/loss");
            all.months().forEach(m -> months.row(m.month(), m.played(), m.won(), m.invested(), m.netPl()));
            months.fit();

            Sheets.Table contests = book.table("My contests", "Starts", "League", "Match", "Name", "Team A",
                    "Team B", "My pick", "Winner", "Result", "Spent", "Won", "Net");
            for (MyResultRow r : rows) {
                contests.row(r.startTime(), r.leagueName() == null ? "One-off" : r.leagueName(), r.contestNumber(),
                        r.contestName(), r.teamA(), r.teamB(), orDash(r.picked()), r.winner(),
                        r.result() == null ? "Waiting" : label(r.result()), r.entrySpent(), r.winningAmount(),
                        r.result() == null ? null : r.net());
            }
            contests.fit();

            Sheets.Table leagueSheet = book.table("My leagues", "League", "Organizer", "League status",
                    "My status", "Paid", "Buy-in", "Hand back to me", "Settled");
            jdbc.query("""
                    select l.name, o.display_name organizer, l.status, lm.league_member_status, lm.payment_status,
                           l.joining_amount, sr.final_balance, sr.settlement_status
                      from league_members lm
                      join leagues l on l.id = lm.league_id
                      join users o on o.id = l.organizer_id
                      left join settlement_rows sr on sr.league_member_id = lm.id
                     where lm.user_id = :userId
                     order by l.created_at desc
                    """, params, (ResultSet rs) -> {
                leagueSheet.row(rs.getString("name"), rs.getString("organizer"), label(rs.getString("status")),
                        label(rs.getString("league_member_status")), label(rs.getString("payment_status")),
                        rs.getBigDecimal("joining_amount"), rs.getBigDecimal("final_balance"),
                        label(rs.getString("settlement_status")));
            });
            leagueSheet.fit();

            Sheets.Table owed = book.table("Payouts", "Contest starts", "Team A", "Team B", "Type", "Amount",
                    "Status", "Paid on");
            jdbc.query("""
                    select c.start_time, ta.short_code a, tb.short_code b, p.payout_type, p.amount, p.payout_status,
                           p.paid_out_at
                      from payouts p
                      join contests c on c.id = p.contest_id
                      join teams ta on ta.id = c.team_a_id
                      join teams tb on tb.id = c.team_b_id
                     where p.user_id = :userId and p.payout_status <> 'VOID'
                     order by c.start_time
                    """, params, (ResultSet rs) -> {
                owed.row(time(rs, "start_time"), rs.getString("a"), rs.getString("b"), label(rs.getString("payout_type")),
                        rs.getBigDecimal("amount"), label(rs.getString("payout_status")), time(rs, "paid_out_at"));
            });
            owed.fit();

            // For organizers: everyone in the one-off contests they run, with what each is owed.
            Sheets.Table organised = book.table("Contests I organise", "Starts", "Team A", "Team B", "Entry",
                    "Status", "Winner", "Player", "Place", "Payment", "Pick", "Result", "Payout", "Payout status");
            jdbc.query("""
                    select c.start_time, ta.short_code a, tb.short_code b, c.contest_amount, c.contest_status,
                           w.short_code winner, u.display_name, cp.participant_status, cp.payment_status,
                           s.short_code picked, cp.result, p.amount payout, p.payout_status
                      from contests c
                      join teams ta on ta.id = c.team_a_id
                      join teams tb on tb.id = c.team_b_id
                      left join teams w on w.id = c.winning_team_id
                      join contest_participants cp on cp.contest_id = c.id
                      join users u on u.id = cp.user_id
                      left join teams s on s.id = cp.selected_team_id
                                        and c.contest_status <> 'SCHEDULED'
                      left join payouts p on p.contest_participant_id = cp.id and p.payout_status <> 'VOID'
                     where c.organizer_id = :userId and c.league_id is null
                     order by c.start_time, u.display_name
                    """, params, (ResultSet rs) -> {
                organised.row(time(rs, "start_time"), rs.getString("a"), rs.getString("b"),
                        rs.getBigDecimal("contest_amount"), label(rs.getString("contest_status")),
                        rs.getString("winner"), rs.getString("display_name"), label(rs.getString("participant_status")),
                        label(rs.getString("payment_status")), rs.getString("picked"), label(rs.getString("result")),
                        rs.getBigDecimal("payout"), label(rs.getString("payout_status")));
            });
            organised.fit();

            return new Export(fileName(me.getDisplayName() + " Dugout XI history", now), book.toBytes());
        }
    }

    // ---------- helpers ----------

    private static Instant time(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    /** RESULT_DECLARED → "Result declared". */
    static String label(String code) {
        if (code == null) return null;
        String words = code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String orDash(String value) {
        return value == null ? "—" : value;
    }

    /** "Office IPL season 2026-11-06.xlsx", with anything awkward in a file name taken out. */
    static String fileName(String base, Instant now) {
        String safe = base.replaceAll("[^A-Za-z0-9 ._-]", "").trim().replaceAll("\\s+", " ");
        if (safe.isEmpty()) safe = "Dugout XI export";
        return safe + " " + now.atZone(Sheets.ZONE).toLocalDate() + ".xlsx";
    }
}
