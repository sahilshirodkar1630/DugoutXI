package com.sahilprojects.bettingApp.dashboard;

import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.contest.Contest;
import com.sahilprojects.bettingApp.contest.ContestRepository;
import com.sahilprojects.bettingApp.contest.ContestStatus;
import com.sahilprojects.bettingApp.dashboard.dto.*;
import com.sahilprojects.bettingApp.dashboard.dto.MySummaryResponse.PendingItem;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.league.LeagueRepository;
import com.sahilprojects.bettingApp.notification.NotificationRepository;
import com.sahilprojects.bettingApp.payout.Payout;
import com.sahilprojects.bettingApp.payout.PayoutRepository;
import com.sahilprojects.bettingApp.payout.PayoutStatus;
import com.sahilprojects.bettingApp.settlement.SettlementRow;
import com.sahilprojects.bettingApp.settlement.SettlementRowRepository;
import com.sahilprojects.bettingApp.settlement.SettlementStatus;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that is read rather than changed: the league table, a user's record, and the home screen.
 *
 * None of these numbers are stored. They are worked out from the contests that have been played, through three
 * database views, so there is no second copy of the money to drift out of step with the first.
 */
@Service
public class DashboardService {

    private final DashboardQueryRepository queries;
    private final LeagueRepository leagues;
    private final ContestRepository contests;
    private final PayoutRepository payouts;
    private final SettlementRowRepository settlementRows;
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final AccessGuard accessGuard;
    private final java.time.Clock clock;

    public DashboardService(DashboardQueryRepository queries, LeagueRepository leagues, ContestRepository contests,
                            PayoutRepository payouts, SettlementRowRepository settlementRows,
                            NotificationRepository notifications, UserRepository users, AccessGuard accessGuard,
                            java.time.Clock clock) {
        this.clock = clock;
        this.queries = queries;
        this.leagues = leagues;
        this.contests = contests;
        this.payouts = payouts;
        this.settlementRows = settlementRows;
        this.notifications = notifications;
        this.users = users;
        this.accessGuard = accessGuard;
    }

    // ---------- the league table ----------

    /** The whole league table. Members only. */
    @Transactional(readOnly = true)
    public LeagueBoardResponse leagueBoard(AuthenticatedUser actor, Long leagueId) {
        League league = leagues.findById(leagueId).orElseThrow(() -> new NotFoundException("League not found"));
        accessGuard.requireLeagueMember(actor, league);

        List<LeagueBoardRowResponse> rows = new ArrayList<>();
        LeagueBoardRowResponse mine = null;
        for (LeagueBoardRow row : queries.leagueBoard(leagueId)) {
            boolean me = row.userId() == actor.id();
            LeagueBoardRowResponse response = new LeagueBoardRowResponse(
                    row.rank(), row.userId(), row.displayName(),
                    row.wonCount(), row.lostCount(), row.refundedCount(),
                    row.winnings(), row.entriesSpent(), row.netPl(),
                    row.joiningAmountPaid(), row.runningBalance(), row.settlementStatus(), me);
            rows.add(response);
            if (me) {
                mine = response;
            }
        }

        int played = 0;
        BigDecimal leftover = BigDecimal.ZERO;
        for (Contest contest : contests.findAllByLeagueWithTeams(leagueId)) {
            if (contest.getContestStatus() == ContestStatus.RESULT_DECLARED
                    || contest.getContestStatus() == ContestStatus.SETTLED) {
                played++;
                leftover = leftover.add(contest.getLeftoverAmount());
            }
        }

        return new LeagueBoardResponse(leagueId, league.getName(), league.getStatus().name(),
                played, league.getTotalContests(), league.getAmountPerContest(), league.getJoiningAmount(),
                leftover, List.copyOf(rows), mine);
    }

    /** Just the caller's own line, for a compact widget. */
    @Transactional(readOnly = true)
    public LeagueBoardRowResponse myLeagueLine(AuthenticatedUser actor, Long leagueId) {
        LeagueBoardResponse board = leagueBoard(actor, leagueId);
        if (board.me() == null) {
            throw new NotFoundException("You have no line on this board yet. Only paid members appear on it");
        }
        return board.me();
    }

    // ---------- the caller's own record ----------

    @Transactional(readOnly = true)
    public MyStatsResponse myStats(AuthenticatedUser actor) {
        User user = users.findById(actor.id()).orElseThrow(() -> new NotFoundException("User not found"));
        UserStatsRow row = queries.userStats(actor.id())
                .orElse(new UserStatsRow(actor.id(), 0, 0, 0, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        return new MyStatsResponse(row.userId(), user.getDisplayName(),
                row.leaguesJoined(), row.contestsPlayed(), row.contestsWon(), row.contestsLost(),
                row.contestsRefunded(), row.amountWon(), row.amountLost(), row.netPl(), row.winPercentage());
    }

    /** The record over one period, for the profile screen. */
    @Transactional(readOnly = true)
    public PeriodStatsResponse periodStats(AuthenticatedUser actor, StatsRange range) {
        java.time.Instant now = clock.instant();
        java.time.Instant from = range.startFrom(now);
        return PeriodStats.compute(range, from, now, queries.myResults(actor.id(), from));
    }

    /** The home screen: the record, plus every open piece of money, in both directions. */
    @Transactional(readOnly = true)
    public MySummaryResponse mySummary(AuthenticatedUser actor) {
        MyStatsResponse stats = myStats(actor);

        List<PendingItem> pendingPayouts = new ArrayList<>();
        BigDecimal payoutTotal = BigDecimal.ZERO;
        for (Payout payout : payouts.findForUserWithContest(actor.id(), PayoutStatus.PENDING)) {
            Contest contest = payout.getContest();
            pendingPayouts.add(new PendingItem("PAYOUT", contest.getId(),
                    contest.getTeamA().getShortCode() + " vs " + contest.getTeamB().getShortCode(),
                    payout.getAmount(), true, payout.getPayoutType().name()));
            payoutTotal = payoutTotal.add(payout.getAmount());
        }

        List<PendingItem> pendingSettlements = new ArrayList<>();
        BigDecimal settlementTotal = BigDecimal.ZERO;
        for (SettlementRow row : settlementRows.findByUser_IdAndSettlementStatus(actor.id(), SettlementStatus.PENDING)) {
            // The buy-in was paid up front, so what is pending is the whole final balance coming back to the user.
            pendingSettlements.add(new PendingItem("SETTLEMENT", row.getLeague().getId(),
                    row.getLeague().getName(), row.getFinalBalance(), true, "PENDING"));
            settlementTotal = settlementTotal.add(row.getFinalBalance());
        }

        List<PendingItem> awaiting = organizerToDoList(actor);

        return new MySummaryResponse(stats,
                (int) notifications.countByUser_IdAndSentAtIsNotNullAndReadAtIsNull(actor.id()),
                payoutTotal, List.copyOf(pendingPayouts),
                settlementTotal, List.copyOf(pendingSettlements),
                List.copyOf(awaiting));
    }

    /**
     * What is waiting on the caller as an organizer: contests that have locked without a result, and leagues
     * that have ended but not finished settling.
     */
    private List<PendingItem> organizerToDoList(AuthenticatedUser actor) {
        List<PendingItem> awaiting = new ArrayList<>();
        for (Contest contest : contests.findLockedWithoutResultForOrganizer(actor.id(), ContestStatus.LOCKED)) {
            awaiting.add(new PendingItem("RESULT_DUE", contest.getId(),
                    contest.getTeamA().getShortCode() + " vs " + contest.getTeamB().getShortCode(),
                    contest.getContestAmount(), false, "LOCKED"));
        }
        for (League league : leagues.findByOrganizer_IdAndStatus(actor.id(),
                com.sahilprojects.bettingApp.league.LeagueStatus.PENDING_SETTLEMENT)) {
            long stillPending = settlementRows
                    .findByLeague_IdAndSettlementStatus(league.getId(), SettlementStatus.PENDING).size();
            if (stillPending > 0) {
                awaiting.add(new PendingItem("SETTLEMENT_DUE", league.getId(), league.getName(),
                        BigDecimal.valueOf(stillPending), false, "PENDING_SETTLEMENT"));
            }
        }
        return awaiting;
    }
}
