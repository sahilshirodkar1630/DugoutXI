package com.sahilprojects.bettingApp.jobs;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.contest.*;
import com.sahilprojects.bettingApp.league.*;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.payout.PayoutService;
import com.sahilprojects.bettingApp.settlement.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What happens the moment a contest's start time arrives.
 *
 * Predictions shut, and the entries that are in the pool are fixed from then on, so this is the point of no return
 * for a contest. It runs in three steps:
 *
 *  1. Anyone accepted but still unpaid is taken out, so only money the organizer actually holds counts.
 *  2. If too few people are left to make a contest of it, the contest is cancelled and standalone entries refunded.
 *  3. Otherwise the contest locks, and everyone is told that predictions are closed.
 *
 * A league's first contest starting is also the league starting: it moves to ACTIVE, and requests still waiting are
 * withdrawn because joining late is not supported. If the league has too few paid members to play at all, it is
 * cancelled instead and settlement rows are written so the organizer can return what was paid in.
 *
 * Each contest is handled in its own transaction, so one contest in a strange state cannot stop the others, and
 * each row is locked with skip-locked so two instances running this together never process the same contest twice.
 */
@Component
public class ContestStartJob {

    private static final Logger log = LoggerFactory.getLogger(ContestStartJob.class);

    private final ContestRepository contests;
    private final ContestParticipantRepository participants;
    private final LeagueRepository leagues;
    private final LeagueMemberRepository leagueMembers;
    private final PaymentCutoffJob paymentCutoff;
    private final PayoutService payoutService;
    private final SettlementService settlementService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * This bean, through its Spring proxy. Calling a transactional method on `this` goes straight to the method
     * and skips the proxy, so the transaction annotation on startOne would have no effect. An ObjectProvider is
     * used rather than a plain field because injecting a bean into itself eagerly would be a circular reference.
     */
    private final ObjectProvider<ContestStartJob> self;

    /**
     * The smallest number of confirmed players a contest can run with. Two is the real floor: with one entry there
     * is no pool to split and the calculator would refund it anyway.
     */
    private final int minimumPlayers;

    public ContestStartJob(ContestRepository contests, ContestParticipantRepository participants,
                           LeagueRepository leagues, LeagueMemberRepository leagueMembers,
                           PaymentCutoffJob paymentCutoff, PayoutService payoutService,
                           SettlementService settlementService, NotificationService notificationService,
                           AuditService auditService, Clock clock,
                           ObjectProvider<ContestStartJob> self,
                           @Value("${app.contests.minimum-players:2}") int minimumPlayers) {
        this.contests = contests;
        this.participants = participants;
        this.leagues = leagues;
        this.leagueMembers = leagueMembers;
        this.paymentCutoff = paymentCutoff;
        this.payoutService = payoutService;
        this.settlementService = settlementService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.clock = clock;
        this.self = self;
        this.minimumPlayers = Math.max(2, minimumPlayers);
    }

    /**
     * Handles every contest whose start time has passed. Returns how many it dealt with.
     *
     * Not transactional itself, on purpose: each contest gets its own transaction so one contest in a strange
     * state cannot roll back the others. It goes through `self` rather than calling startOne directly, because a
     * call on `this` would skip the Spring proxy and the REQUIRES_NEW would quietly do nothing.
     */
    public int run() {
        // The cutoff runs first and across the board, so a contest about to lock never counts an unpaid entry.
        paymentCutoff.run();

        List<Long> due = contests.findIdsDueToStart(ContestStatus.SCHEDULED, clock.instant());
        int handled = 0;
        for (Long contestId : due) {
            try {
                self.getObject().startOne(contestId);
                handled++;
            } catch (RuntimeException e) {
                // One contest in a strange state must not hold up the rest. It will be retried on the next run.
                log.error("Could not start contest {}: {}", contestId, e.getMessage(), e);
            }
        }
        if (handled > 0) {
            log.info("Start job handled {} of {} due contest(s)", handled, due.size());
        }
        return handled;
    }

    /**
     * Locks, cancels or leaves alone one contest. REQUIRES_NEW because the caller loops over many of them and a
     * failure on one must not roll back the others.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContestStatus startOne(Long contestId) {
        Contest contest = contests.findByIdForUpdate(contestId).orElse(null);
        if (contest == null) {
            return null;
        }
        Instant now = clock.instant();
        if (contest.getContestStatus() != ContestStatus.SCHEDULED || now.isBefore(contest.getStartTime())) {
            return contest.getContestStatus();                   // another run got there first, or it was rescheduled
        }

        // A league starts with its first contest, and may turn out not to have enough players to start at all.
        if (contest.getLeague() != null && !startLeagueIfNeeded(contest.getLeague(), now)) {
            contest.setContestStatus(ContestStatus.CANCELLED);
            contest.setCancelledReason("The league did not have enough paid members to start");
            contests.saveAndFlush(contest);
            notificationService.cancelContestReminders(contestId);
            return ContestStatus.CANCELLED;
        }

        // Requests nobody answered before the start go nowhere: a place cannot be granted once the pool is fixed.
        if (contest.isStandalone()) {
            withdrawUnansweredRequests(contest);
        }

        List<ContestParticipant> confirmed = participants.findByStatusWithUser(contestId, ParticipantStatus.CONFIRMED);
        if (confirmed.size() < minimumPlayers) {
            return cancelForTooFewPlayers(contest, confirmed, now);
        }
        return lock(contest, confirmed, now);
    }

    // ---------- the three outcomes ----------

    private ContestStatus lock(Contest contest, List<ContestParticipant> confirmed, Instant now) {
        contest.setContestStatus(ContestStatus.LOCKED);
        contest.setLockedAt(now);
        contests.saveAndFlush(contest);
        notificationService.cancelContestReminders(contest.getId());

        int withoutAPick = 0;
        for (ContestParticipant row : confirmed) {
            if (row.getSelectedTeam() == null) {
                withoutAPick++;
            }
            notificationService.notifyOnce(row.getUser().getId(), NotificationType.PREDICTIONS_LOCKED,
                    ReferenceType.CONTEST, contest.getId(),
                    "Predictions are closed for " + label(contest),
                    row.getSelectedTeam() == null
                            ? "You did not make a pick, so your entry counts towards the pool but cannot win."
                            : "You picked " + row.getSelectedTeam().getShortCode() + ". Good luck.",
                    "locked");
        }

        auditService.record(AuditEntry.builder()
                .action(AuditAction.CONTEST_LOCKED)
                .entityType("contest").entityId(contest.getId())
                .leagueId(contest.getLeague() == null ? null : contest.getLeague().getId())
                .contestId(contest.getId())
                .actorType(ActorType.SYSTEM)
                .reason("The contest started")
                .before(Map.of("status", "SCHEDULED"))
                .after(Map.of("status", "LOCKED", "confirmedPlayers", confirmed.size(),
                        "withoutAPick", withoutAPick))
                .build());
        return ContestStatus.LOCKED;
    }

    private ContestStatus cancelForTooFewPlayers(Contest contest, List<ContestParticipant> confirmed, Instant now) {
        String reason = "Only " + confirmed.size() + " confirmed player(s), and a contest needs at least "
                + minimumPlayers;
        contest.setContestStatus(ContestStatus.CANCELLED);
        contest.setCancelledReason(reason);

        int refunds = 0;
        if (contest.isStandalone()) {
            refunds = payoutService.createRefundsForCancelledContest(contest,
                    participants.findPaidWithUser(contest.getId(), ContestPaymentStatus.PAID)).size();
        }
        contests.saveAndFlush(contest);
        notificationService.cancelContestReminders(contest.getId());

        for (ContestParticipant row : participants.findByContestWithUser(contest.getId())) {
            notificationService.notifyOnce(row.getUser().getId(), NotificationType.CONTEST_CANCELLED,
                    ReferenceType.CONTEST, contest.getId(),
                    label(contest) + " was cancelled",
                    reason + (refunds > 0 ? ". Your entry will be refunded by the organizer." : "."),
                    "cancelled");
        }

        auditService.record(AuditEntry.builder()
                .action(AuditAction.CONTEST_CANCELLED)
                .entityType("contest").entityId(contest.getId())
                .leagueId(contest.getLeague() == null ? null : contest.getLeague().getId())
                .contestId(contest.getId())
                .actorType(ActorType.SYSTEM)
                .reason(reason)
                .before(Map.of("status", "SCHEDULED"))
                .after(Map.of("status", "CANCELLED", "refundsCreated", refunds))
                .build());
        log.info("Contest {} cancelled: {}", contest.getId(), reason);
        return ContestStatus.CANCELLED;
    }

    /**
     * Moves a league to ACTIVE when its first contest starts, and withdraws requests that are still waiting,
     * because joining a league after it has started is not supported.
     *
     * Returns false when the league could not start at all, in which case it has been cancelled.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean startLeagueIfNeeded(League leagueRef, Instant now) {
        League league = leagues.findByIdForUpdate(leagueRef.getId()).orElseThrow();
        if (league.getStatus() == LeagueStatus.ACTIVE) {
            return true;
        }
        if (league.getStatus() != LeagueStatus.OPEN && league.getStatus() != LeagueStatus.UPCOMING) {
            return league.getStatus() != LeagueStatus.CANCELLED;   // ended or settling: nothing to start
        }

        long paidMembers = leagueMembers.countByLeague_IdAndLeagueMemberStatus(
                league.getId(), LeagueMemberStatus.CONFIRMED);
        if (paidMembers < minimumPlayers) {
            cancelLeagueThatCannotStart(league, paidMembers, now);
            return false;
        }

        withdrawPendingRequests(league);
        league.setStatus(LeagueStatus.ACTIVE);
        league.setStartedAt(now);
        leagues.saveAndFlush(league);

        auditService.record(AuditEntry.builder()
                .action(AuditAction.LEAGUE_STARTED)
                .entityType("league").entityId(league.getId()).leagueId(league.getId())
                .actorType(ActorType.SYSTEM)
                .reason("The first contest started")
                .before(Map.of("status", "OPEN"))
                .after(Map.of("status", "ACTIVE", "startedAt", now.toString(), "paidMembers", paidMembers))
                .build());

        for (LeagueMember member : leagueMembers.findByLeague_IdAndLeagueMemberStatus(
                league.getId(), LeagueMemberStatus.CONFIRMED)) {
            notificationService.notifyOnce(member.getUser().getId(), NotificationType.CONTEST_REMINDER,
                    ReferenceType.LEAGUE, league.getId(),
                    league.getName() + " has started",
                    paidMembers + " players are in. Good luck.", "league-started");
        }
        log.info("League {} started with {} paid member(s)", league.getId(), paidMembers);
        return true;
    }

    private void cancelLeagueThatCannotStart(League league, long paidMembers, Instant now) {
        String reason = "Only " + paidMembers + " paid member(s) by the first contest, and a league needs at least "
                + minimumPlayers;
        for (Contest other : contests.findByLeague_IdAndContestStatus(league.getId(), ContestStatus.SCHEDULED)) {
            other.setContestStatus(ContestStatus.CANCELLED);
            other.setCancelledReason(reason);
        }
        withdrawPendingRequests(league);

        int refundRows = 0;
        if (paidMembers > 0) {
            refundRows = settlementService.writeRows(league, now).size();
        }
        league.setStatus(LeagueStatus.CANCELLED);
        league.setEndedAt(now);
        leagues.saveAndFlush(league);

        auditService.record(AuditEntry.builder()
                .action(AuditAction.LEAGUE_CANCELLED)
                .entityType("league").entityId(league.getId()).leagueId(league.getId())
                .actorType(ActorType.SYSTEM)
                .reason(reason)
                .after(Map.of("status", "CANCELLED", "settlementRows", refundRows))
                .build());

        for (LeagueMember member : leagueMembers.findByLeague_IdAndLeagueMemberStatus(
                league.getId(), LeagueMemberStatus.CONFIRMED)) {
            notificationService.notifyOnce(member.getUser().getId(), NotificationType.LEAGUE_CANCELLED,
                    ReferenceType.LEAGUE, league.getId(),
                    league.getName() + " was cancelled",
                    reason + ". Your joining amount will be returned by the organizer.", "league-cancelled");
        }
        log.info("League {} cancelled before starting: {}", league.getId(), reason);
    }

    /** Requests still waiting when a league starts go nowhere, so they are withdrawn rather than left hanging. */
    private void withdrawPendingRequests(League league) {
        for (LeagueMember member : leagueMembers.findByLeague_IdAndLeagueMemberStatus(
                league.getId(), LeagueMemberStatus.PENDING_APPROVAL)) {
            member.setLeagueMemberStatus(LeagueMemberStatus.CANCELLED);
            member.setStatusReason("The league started before this request was accepted");
            notificationService.notifyNow(member.getUser().getId(), NotificationType.JOIN_REQUEST_DECISION,
                    ReferenceType.LEAGUE, league.getId(),
                    league.getName() + " has started without you",
                    "Your request was still waiting when the league started. Joining late is not supported.");
        }
    }

    /** The standalone counterpart of withdrawPendingRequests: unanswered requests are closed and the asker told. */
    private void withdrawUnansweredRequests(Contest contest) {
        for (ContestParticipant row : participants.findByStatusWithUser(contest.getId(),
                ParticipantStatus.PENDING_APPROVAL)) {
            row.setParticipantStatus(ParticipantStatus.CANCELLED);
            row.setStatusReason("The contest started before this request was answered");
            notificationService.notifyNow(row.getUser().getId(), NotificationType.JOIN_REQUEST_DECISION,
                    ReferenceType.CONTEST, contest.getId(),
                    label(contest) + " has started without you",
                    "Your request was still waiting when the contest started.");
        }
    }

    private static String label(Contest contest) {
        if (contest.getName() != null && !contest.getName().isBlank()) {
            return contest.getName();
        }
        return contest.getTeamA().getShortCode() + " vs " + contest.getTeamB().getShortCode();
    }
}
