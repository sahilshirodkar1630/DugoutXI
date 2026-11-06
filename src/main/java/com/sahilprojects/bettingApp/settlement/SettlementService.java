package com.sahilprojects.bettingApp.settlement;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.ForbiddenException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.contest.Contest;
import com.sahilprojects.bettingApp.contest.ContestRepository;
import com.sahilprojects.bettingApp.contest.ContestStatus;
import com.sahilprojects.bettingApp.dashboard.DashboardQueryRepository;
import com.sahilprojects.bettingApp.dashboard.SettlementCalcRow;
import com.sahilprojects.bettingApp.league.*;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.settlement.dto.SettlementRowResponse;
import com.sahilprojects.bettingApp.settlement.dto.SettlementSummaryResponse;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closing a league and working out who owes whom.
 *
 * Everyone paid the whole season up front, so the organizer holds the pot. Each contest only records what a member
 * won and what their entry cost, and a member's final balance is the joining amount they paid plus everything they
 * won minus everything they spent. That final balance is what the organizer hands back to them. Ending the league
 * freezes it into one settlement row per paid member, which is what the members actually act on.
 *
 * The organizer's own row needs no transfer and is marked settled straight away, as is anyone whose final balance is
 * zero: they lost their whole buy-in, so there is nothing to return. Everyone else is paid back outside the app and
 * the organizer ticks them off.
 *
 * An add-up check guards the whole thing: the balances must add up to what was paid in, less the paise a pool could
 * not divide evenly. A league cannot be completed while that check fails.
 */
@Service
public class SettlementService {

    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final ContestRepository contests;
    private final SettlementRowRepository rows;
    private final UserRepository users;
    private final DashboardQueryRepository dashboard;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    public SettlementService(LeagueRepository leagues, LeagueMemberRepository members, ContestRepository contests,
                             SettlementRowRepository rows, UserRepository users, DashboardQueryRepository dashboard,
                             AccessGuard accessGuard, AuditService auditService,
                             NotificationService notificationService, Clock clock) {
        this.leagues = leagues;
        this.members = members;
        this.contests = contests;
        this.rows = rows;
        this.users = users;
        this.dashboard = dashboard;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---------- ending the league ----------

    /**
     * Ends an ACTIVE league and writes the settlement rows.
     *
     * Refused while any contest still owes a result, because a missing result would quietly change everybody's
     * balance later. Contests that have not started yet are cancelled: the league is over, so they will not happen.
     */
    @Transactional
    public SettlementSummaryResponse end(AuthenticatedUser actor, Long leagueId, String reason) {
        League league = lockLeague(leagueId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, reason);
        if (league.getStatus() != LeagueStatus.ACTIVE) {
            throw new ConflictException("Only an active league can be ended (this one is " + league.getStatus() + ")");
        }
        requireEveryContestIsDone(leagueId);

        Instant now = clock.instant();
        int cancelled = 0;
        for (Contest contest : contests.findByLeague_IdAndContestStatus(leagueId, ContestStatus.SCHEDULED)) {
            contest.setContestStatus(ContestStatus.CANCELLED);
            contest.setCancelledReason("The league ended before this contest was played");
            cancelled++;
        }

        List<SettlementRow> created = writeRows(league, now);
        league.setStatus(LeagueStatus.PENDING_SETTLEMENT);
        league.setEndedAt(now);
        leagues.saveAndFlush(league);

        auditService.record(AuditEntry.builder()
                .action(AuditAction.LEAGUE_ENDED)
                .entityType("league").entityId(leagueId).leagueId(leagueId)
                .actorId(actor.id()).actorType(actingAs)
                .reason(reason)
                .after(Map.of("status", "PENDING_SETTLEMENT", "settlementRows", created.size(),
                        "contestsCancelled", cancelled))
                .build());

        for (SettlementRow row : created) {
            notificationService.notifyOnce(row.getUser().getId(), NotificationType.LEAGUE_ENDED,
                    ReferenceType.LEAGUE, leagueId,
                    league.getName() + " has ended",
                    settlementSentence(row, league), "ended");
        }
        return toSummaryResponse(league, created, true, actor.id());
    }

    /**
     * Writes one row per paid member. Called when a league ends, and when a cancelled league has to return
     * money that members had already paid in.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SettlementRow> writeRows(League league, Instant now) {
        if (!rows.findByLeague_IdOrderByFinalBalanceDesc(league.getId()).isEmpty()) {
            throw new ConflictException("This league already has settlement rows");
        }
        Long organizerId = league.getOrganizer().getId();
        List<SettlementRow> created = new ArrayList<>();
        for (SettlementCalcRow calc : dashboard.settlementCalc(league.getId())) {
            SettlementRow row = new SettlementRow();
            row.setLeague(league);
            row.setLeagueMember(members.getReferenceById(calc.leagueMemberId()));
            row.setUser(users.getReferenceById(calc.userId()));
            row.setOrganizer(calc.userId() == organizerId.longValue());
            row.setJoiningAmountPaid(calc.joiningAmountPaid());
            row.setNetPl(calc.netPl());
            row.setFinalBalance(calc.finalBalance());

            // The organizer holds the money, and a member with nothing left has nothing to be handed back.
            boolean nothingToTransfer = row.isOrganizer() || calc.finalBalance().signum() == 0;
            if (nothingToTransfer) {
                row.setSettlementStatus(SettlementStatus.SETTLED);
                row.setSettledAt(now);
                row.setSettledBy(users.getReferenceById(organizerId));
            }
            created.add(row);
        }
        return rows.saveAll(created);
    }

    // ---------- reading ----------

    @Transactional(readOnly = true)
    public List<SettlementRowResponse> list(AuthenticatedUser actor, Long leagueId) {
        League league = load(leagueId);
        accessGuard.requireLeagueMember(actor, league);
        boolean privileged = isPrivileged(actor, league);
        return rows.findByLeague_IdOrderByFinalBalanceDesc(leagueId).stream()
                .map(r -> toResponse(r, privileged, actor.id()))
                .toList();
    }

    /** The whole picture, including who is owed, who owes, and whether the figures add up. */
    @Transactional(readOnly = true)
    public SettlementSummaryResponse summary(AuthenticatedUser actor, Long leagueId) {
        League league = load(leagueId);
        accessGuard.requireLeagueMember(actor, league);
        boolean privileged = isPrivileged(actor, league);
        List<SettlementRow> all = rows.findByLeague_IdOrderByFinalBalanceDesc(leagueId);
        if (all.isEmpty()) {
            throw new ConflictException("This league has not ended yet, so there is nothing to settle");
        }
        return toSummaryResponse(league, all, privileged, actor.id());
    }

    // ---------- settling up ----------

    /** The organizer ticks off a member who has settled, or puts a row back if they got it wrong. */
    @Transactional
    public SettlementRowResponse updateStatus(AuthenticatedUser actor, Long leagueId, Long rowId,
                                              String statusName, String reason) {
        League league = lockLeague(leagueId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, reason);
        if (league.getStatus() != LeagueStatus.PENDING_SETTLEMENT && league.getStatus() != LeagueStatus.CANCELLED) {
            throw new ConflictException("Settlement rows can only be changed while the league is settling up");
        }
        SettlementRow row = rows.findById(rowId)
                .filter(r -> r.getLeague().getId().equals(leagueId))
                .orElseThrow(() -> new NotFoundException("Settlement row not found"));

        SettlementStatus target = parseStatus(statusName);
        SettlementStatus current = row.getSettlementStatus();
        if (current == target) {
            return toResponse(row, true, actor.id());
        }
        if (current == SettlementStatus.WRITTEN_OFF) {
            throw new ConflictException("This row was written off when the league was force-completed");
        }
        if (target == SettlementStatus.PENDING && (reason == null || reason.isBlank())) {
            throw new BadRequestException("A reason is required to mark a settled member as pending again");
        }

        Instant now = clock.instant();
        row.setSettlementStatus(target);
        if (target == SettlementStatus.SETTLED) {
            row.setSettledAt(now);
            row.setSettledBy(users.getReferenceById(actor.id()));
        } else {
            row.setSettledAt(null);
            row.setSettledBy(null);
        }

        auditService.record(AuditEntry.builder()
                .action(AuditAction.SETTLEMENT_MARKED)
                .entityType("settlement_row").entityId(rowId).leagueId(leagueId)
                .actorId(actor.id()).actorType(actingAs)
                .reason(reason)
                .before(Map.of("settlementStatus", current.name()))
                .after(Map.of("settlementStatus", target.name()))
                .build());

        notificationService.notifyNow(row.getUser().getId(), NotificationType.SETTLEMENT_UPDATED,
                ReferenceType.LEAGUE, leagueId,
                "Your settlement in " + league.getName() + " was marked " + target.name().toLowerCase(),
                reason);
        return toResponse(row, true, actor.id());
    }

    /** Closes a league once everybody has settled. */
    @Transactional
    public SettlementSummaryResponse complete(AuthenticatedUser actor, Long leagueId, String reason) {
        League league = lockLeague(leagueId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, reason);
        if (league.getStatus() != LeagueStatus.PENDING_SETTLEMENT) {
            throw new ConflictException("Only a league that is settling up can be completed (this one is "
                    + league.getStatus() + ")");
        }
        List<SettlementRow> all = rows.findByLeague_IdOrderByFinalBalanceDesc(leagueId);
        List<SettlementRow> pending = all.stream()
                .filter(r -> r.getSettlementStatus() == SettlementStatus.PENDING)
                .toList();
        if (!pending.isEmpty()) {
            throw new ConflictException("Still waiting on " + pending.size() + " member(s): " + whoIsOutstanding(pending));
        }
        if (!dashboard.settlementBalances(leagueId)) {
            throw new ConflictException(
                    "The settlement figures do not add up, so the league cannot be completed. Please report this");
        }

        Instant now = clock.instant();
        league.setStatus(LeagueStatus.COMPLETED);
        league.setCompletedAt(now);
        leagues.saveAndFlush(league);

        auditService.record(AuditEntry.builder()
                .action(AuditAction.LEAGUE_COMPLETED)
                .entityType("league").entityId(leagueId).leagueId(leagueId)
                .actorId(actor.id()).actorType(actingAs)
                .reason(reason)
                .after(Map.of("status", "COMPLETED", "completedAt", now.toString()))
                .build());

        for (SettlementRow row : all) {
            notificationService.notifyOnce(row.getUser().getId(), NotificationType.SETTLEMENT_UPDATED,
                    ReferenceType.LEAGUE, leagueId,
                    league.getName() + " is settled and closed",
                    "Everybody has settled up. Thanks for playing.", "completed");
        }
        return toSummaryResponse(league, all, true, actor.id());
    }

    /**
     * Closes a league that will never finish settling: an admin writes off whatever is still outstanding,
     * with a reason, so the league can be put to rest rather than sitting open for ever.
     */
    @Transactional
    public SettlementSummaryResponse forceComplete(AuthenticatedUser actor, Long leagueId, String reason) {
        League league = lockLeague(leagueId);
        accessGuard.requireAdminWithReason(actor, reason);
        if (league.getStatus() != LeagueStatus.PENDING_SETTLEMENT) {
            throw new ConflictException("Only a league that is settling up can be force-completed");
        }

        Instant now = clock.instant();
        List<SettlementRow> all = rows.findByLeague_IdOrderByFinalBalanceDesc(leagueId);
        int writtenOff = 0;
        for (SettlementRow row : all) {
            if (row.getSettlementStatus() == SettlementStatus.PENDING) {
                row.setSettlementStatus(SettlementStatus.WRITTEN_OFF);
                row.setWrittenOffReason(reason.trim());
                writtenOff++;
            }
        }
        league.setStatus(LeagueStatus.COMPLETED);
        league.setCompletedAt(now);
        league.setForceClosedBy(users.getReferenceById(actor.id()));
        league.setForceCloseReason(reason.trim());
        leagues.saveAndFlush(league);

        auditService.record(AuditEntry.builder()
                .action(AuditAction.FORCE_COMPLETE)
                .entityType("league").entityId(leagueId).leagueId(leagueId)
                .actorId(actor.id()).actorType(ActorType.ADMIN)
                .reason(reason)
                .after(Map.of("status", "COMPLETED", "writtenOff", writtenOff))
                .build());

        for (SettlementRow row : all) {
            notificationService.notifyOnce(row.getUser().getId(), NotificationType.SETTLEMENT_UPDATED,
                    ReferenceType.LEAGUE, leagueId,
                    league.getName() + " was closed by an admin",
                    reason.trim(), "force-complete");
        }
        return toSummaryResponse(league, all, true, actor.id());
    }

    // ---------- used by the result correction path ----------

    /**
     * Rewrites the balances after a result in an ended league was corrected.
     *
     * Rows still waiting are redone, and so is the organizer's own: they hold the pot, so their balance is the
     * mirror of everybody else's and cannot be left stale. A row that was settled only because its balance was zero
     * is redone as well, since no money moved for it. A member who has actually settled up is left exactly as
     * they are, which is why a correction is refused up front when one of those would have changed.
     *
     * A member row follows the same rule as when the league ended: a zero balance means nothing to return and it is
     * settled, anything else is pending.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recalculatePendingRows(Long leagueId) {
        Map<Long, SettlementCalcRow> fresh = new LinkedHashMap<>();
        for (SettlementCalcRow calc : dashboard.settlementCalc(leagueId)) {
            fresh.put(calc.leagueMemberId(), calc);
        }
        Instant now = clock.instant();
        int changed = 0;
        for (SettlementRow row : rows.findByLeague_IdOrderByFinalBalanceDesc(leagueId)) {
            boolean wasZero = row.getFinalBalance().signum() == 0;
            boolean redoable = row.getSettlementStatus() == SettlementStatus.PENDING
                    || row.isOrganizer()
                    || (row.getSettlementStatus() == SettlementStatus.SETTLED && wasZero);
            if (!redoable) {
                continue;
            }
            SettlementCalcRow calc = fresh.get(row.getLeagueMember().getId());
            if (calc == null || row.getNetPl().compareTo(calc.netPl()) == 0) {
                continue;
            }
            row.setNetPl(calc.netPl());
            row.setFinalBalance(calc.finalBalance());
            // The organizer's row stays settled whatever the figure: there is no transfer to make to themselves.
            if (!row.isOrganizer()) {
                boolean nowZero = calc.finalBalance().signum() == 0;
                if (nowZero) {
                    row.setSettlementStatus(SettlementStatus.SETTLED);
                    row.setSettledAt(now);
                    row.setSettledBy(row.getLeague().getOrganizer());
                } else {
                    row.setSettlementStatus(SettlementStatus.PENDING);
                    row.setSettledAt(null);
                    row.setSettledBy(null);
                }
            }
            changed++;
        }
        return changed;
    }

    // ---------- rules ----------

    /**
     * A league cannot be wrapped up while a contest is still waiting for its result: a locked contest, or one whose
     * start time has passed without the start job having run yet.
     */
    private void requireEveryContestIsDone(Long leagueId) {
        if (contests.existsByLeague_IdAndContestStatus(leagueId, ContestStatus.LOCKED)) {
            throw new ConflictException(
                    "Some contests are still waiting for a result. Enter those results before ending the league");
        }
        Instant now = clock.instant();
        for (Contest contest : contests.findByLeague_IdAndContestStatus(leagueId, ContestStatus.SCHEDULED)) {
            if (!now.isBefore(contest.getStartTime())) {
                throw new ConflictException("A contest has started but has no result yet. "
                        + "Wait for it to lock and enter the result before ending the league");
            }
        }
    }

    private static SettlementStatus parseStatus(String name) {
        if (name == null) {
            throw new BadRequestException("A status is required");
        }
        SettlementStatus status;
        try {
            status = SettlementStatus.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown settlement status: " + name);
        }
        if (status == SettlementStatus.WRITTEN_OFF) {
            throw new ForbiddenException("Only an admin force-completing the league can write a row off");
        }
        return status;
    }

    private static String whoIsOutstanding(List<SettlementRow> pending) {
        return pending.stream()
                .sorted(Comparator.comparing(r -> r.getUser().getDisplayName(),
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(r -> r.getUser().getDisplayName() + " (owed " + r.getFinalBalance().toPlainString() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    // ---------- plumbing ----------

    private League load(Long id) {
        return leagues.findById(id).orElseThrow(() -> new NotFoundException("League not found"));
    }

    private League lockLeague(Long id) {
        return leagues.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("League not found"));
    }

    private boolean isPrivileged(AuthenticatedUser actor, League league) {
        return actor.isAdmin() || league.getOrganizer().getId().equals(actor.id());
    }

    private static String settlementSentence(SettlementRow row, League league) {
        if (row.isOrganizer()) {
            return "You hold the pot in " + league.getName() + ". Hand each member back their final balance, "
                    + "then tick them off.";
        }
        if (row.getFinalBalance().signum() == 0) {
            return "Your whole buy-in of " + row.getJoiningAmountPaid().toPlainString() + " was spent in "
                    + league.getName() + ", so there is nothing to hand back.";
        }
        BigDecimal net = row.getNetPl();
        String howItWent = net.signum() == 0 ? "exactly what you paid in"
                : net.signum() > 0 ? "your buy-in plus " + net.toPlainString() + " in winnings"
                : "your buy-in less " + net.abs().toPlainString();
        return "The organizer will return " + row.getFinalBalance().toPlainString() + " to you: " + howItWent + ".";
    }

    private SettlementSummaryResponse toSummaryResponse(League league, List<SettlementRow> all,
                                                        boolean privileged, Long actorId) {
        BigDecimal totalPaidIn = BigDecimal.ZERO;
        BigDecimal totalFinal = BigDecimal.ZERO;
        int pending = 0;
        int settled = 0;
        int writtenOff = 0;
        List<SettlementRowResponse> owed = new ArrayList<>();
        List<SettlementRowResponse> owes = new ArrayList<>();
        List<SettlementRowResponse> responses = new ArrayList<>(all.size());

        for (SettlementRow row : all) {
            totalPaidIn = totalPaidIn.add(row.getJoiningAmountPaid());
            totalFinal = totalFinal.add(row.getFinalBalance());
            switch (row.getSettlementStatus()) {
                case PENDING -> pending++;
                case SETTLED -> settled++;
                case WRITTEN_OFF -> writtenOff++;
            }
            SettlementRowResponse response = toResponse(row, privileged, actorId);
            responses.add(response);
            // Everyone paid up front, so nobody owes the organizer: the only direction is money coming back.
            if (!row.isOrganizer() && row.getFinalBalance().signum() > 0) {
                owed.add(response);
            }
        }

        BigDecimal leftover = contests.findAllByLeagueWithTeams(league.getId()).stream()
                .filter(c -> c.getContestStatus() == ContestStatus.RESULT_DECLARED
                        || c.getContestStatus() == ContestStatus.SETTLED)
                .map(Contest::getLeftoverAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SettlementSummaryResponse(
                league.getId(), league.getName(), league.getStatus().name(), all.size(),
                totalPaidIn, totalFinal, leftover,
                totalFinal.compareTo(totalPaidIn.subtract(leftover)) == 0,
                pending, settled, writtenOff,
                List.copyOf(owed), List.copyOf(owes), List.copyOf(responses));
    }

    private static SettlementRowResponse toResponse(SettlementRow row, boolean privileged, Long actorId) {
        User user = row.getUser();
        boolean mine = user.getId().equals(actorId);
        boolean showDetails = privileged || mine;
        return new SettlementRowResponse(
                row.getId(), row.getLeague().getId(), user.getId(), user.getDisplayName(),
                showDetails ? user.getPhoneNumber() : null,
                showDetails ? user.getUpiId() : null,
                row.isOrganizer(),
                row.getJoiningAmountPaid(), row.getNetPl(), row.getFinalBalance(),
                row.getSettlementStatus().name(), row.getSettledAt(),
                row.getWrittenOffReason(), mine);
    }
}
