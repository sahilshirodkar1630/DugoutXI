package com.sahilprojects.bettingApp.payout;

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
import com.sahilprojects.bettingApp.contest.*;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.payout.dto.PayoutResponse;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone contests only. A league's money is netted once at the end of the league, so league contests create
 * no payouts at all.
 *
 * The app never moves money. A payout is a record of what the organizer owes somebody, which the organizer marks as
 * handed over once they have actually sent it. The organizer's own payout is marked straight away: there is nothing
 * for them to pay themselves.
 *
 * When every payout is settled or void the contest becomes SETTLED. Taking one back to PENDING reopens the contest,
 * because it is no longer true that everyone has been paid.
 */
@Service
public class PayoutService {

    private final PayoutRepository payouts;
    private final ContestRepository contests;
    private final UserRepository users;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    public PayoutService(PayoutRepository payouts, ContestRepository contests, UserRepository users,
                         AccessGuard accessGuard, AuditService auditService,
                         NotificationService notificationService, Clock clock) {
        this.payouts = payouts;
        this.contests = contests;
        this.users = users;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---------- created by the result and cancel flows ----------

    /**
     * Writes the payouts for a standalone contest whose result has just been confirmed.
     * Winners get a WINNING payout; a refund-all outcome gives everyone a REFUND of their entry.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Payout> createForResult(Contest contest,
                                        List<ContestParticipant> participants,
                                        PayoutCalculator.Settlement settlement) {
        if (!contest.isStandalone()) {
            return List.of();
        }
        Map<Long, ContestParticipant> byId = new LinkedHashMap<>();
        for (ContestParticipant p : participants) {
            byId.put(p.getId(), p);
        }

        List<Payout> created = new ArrayList<>();
        for (PayoutCalculator.Outcome outcome : settlement.outcomes()) {
            ContestParticipant participant = byId.get(outcome.participantId());
            if (participant == null) {
                continue;
            }
            if (settlement.refundAll()) {
                created.add(newPayout(contest, participant, PayoutType.REFUND, contest.getContestAmount()));
            } else if (outcome.won()) {
                created.add(newPayout(contest, participant, PayoutType.WINNING, outcome.winnings()));
            }
            // A loser is owed nothing, so there is no row for them.
        }
        return payouts.saveAll(created);
    }

    /** A cancelled standalone contest: everyone who actually paid gets their entry back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Payout> createRefundsForCancelledContest(Contest contest, List<ContestParticipant> paidParticipants) {
        if (!contest.isStandalone()) {
            return List.of();
        }
        List<Payout> created = new ArrayList<>(paidParticipants.size());
        for (ContestParticipant participant : paidParticipants) {
            created.add(newPayout(contest, participant, PayoutType.REFUND, contest.getContestAmount()));
        }
        return payouts.saveAll(created);
    }

    /**
     * Voids a contest's live payouts so a corrected result can write fresh ones.
     *
     * Pending rows are voided, and so is the organizer's own row even though it is marked PAID_OUT: that mark was
     * made automatically because the organizer holds the pot, so no money actually moved and the corrected result
     * must replace it. Leaving it would either keep a stale amount or collide with the organizer's new payout.
     * Anybody else's paid-out row is never touched: the caller refuses the correction first if one exists.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int voidPayoutsForCorrection(Contest contest) {
        Long organizerId = contest.getOrganizer().getId();
        int voided = 0;
        for (Payout p : payouts.findByContest_Id(contest.getId())) {
            boolean organizersOwnAutoPaid = p.getPayoutStatus() == PayoutStatus.PAID_OUT
                    && p.getUser().getId().equals(organizerId);
            if (p.getPayoutStatus() == PayoutStatus.PENDING || organizersOwnAutoPaid) {
                p.setPayoutStatus(PayoutStatus.VOID);
                p.setPaidOutAt(null);
                p.setPaidOutBy(null);
                voided++;
            }
        }
        // Flush the voids before new rows are inserted, so the one-live-payout-per-participant index sees them.
        payouts.flush();
        return voided;
    }

    /** Called after a payout's status changes: the contest is SETTLED only while nothing is still pending. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshContestSettlement(Contest contest) {
        if (!contest.isStandalone()) {
            return;
        }
        boolean anythingPending = payouts.existsByContest_IdAndPayoutStatus(contest.getId(), PayoutStatus.PENDING);
        if (!anythingPending && contest.getContestStatus() == ContestStatus.RESULT_DECLARED) {
            contest.setContestStatus(ContestStatus.SETTLED);
        } else if (anythingPending && contest.getContestStatus() == ContestStatus.SETTLED) {
            contest.setContestStatus(ContestStatus.RESULT_DECLARED);
        }
    }

    // ---------- the API ----------

    /**
     * The organizer sees every payout for their contest, with the UPI id they need in order to pay.
     * A participant sees only their own, and never anyone else's payment details.
     */
    @Transactional(readOnly = true)
    public List<PayoutResponse> list(AuthenticatedUser actor, Long contestId) {
        Contest contest = contests.findByIdWithDetails(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        if (!contest.isStandalone()) {
            throw new ConflictException("League contests have no payouts; the league is settled once at the end");
        }
        boolean organizerView = contest.getOrganizer().getId().equals(actor.id()) || actor.isAdmin();

        List<PayoutResponse> out = new ArrayList<>();
        for (Payout p : payouts.findByContestWithUser(contestId)) {
            boolean mine = p.getUser().getId().equals(actor.id());
            if (!organizerView && !mine) {
                continue;
            }
            out.add(toResponse(p, contestId, actor.id(), organizerView || mine));
        }
        return List.copyOf(out);
    }

    /**
     * The organizer records that they have handed the money over, or takes that back.
     * Taking it back needs a reason, and it reopens the contest.
     */
    @Transactional
    public PayoutResponse updateStatus(AuthenticatedUser actor, Long contestId, Long payoutId,
                                       String newStatusName, String reason) {
        Contest contest = contests.findByIdForUpdate(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        ActorType actorType = accessGuard.requireOrganizerOrAdmin(actor, contest, reason);

        Payout payout = payouts.findById(payoutId)
                .filter(p -> p.getContest().getId().equals(contestId))
                .orElseThrow(() -> new NotFoundException("Payout not found"));

        PayoutStatus target = parseStatus(newStatusName);
        PayoutStatus current = payout.getPayoutStatus();
        if (current == target) {
            return toResponse(payout, contestId, actor.id(), true);
        }
        if (current == PayoutStatus.VOID) {
            throw new ConflictException("This payout was voided by a result correction and cannot be changed");
        }
        if (target == PayoutStatus.PENDING && (reason == null || reason.isBlank())) {
            throw new BadRequestException("A reason is required to mark a payout as not paid again");
        }

        Instant now = clock.instant();
        payout.setPayoutStatus(target);
        if (target == PayoutStatus.PAID_OUT) {
            payout.setPaidOutAt(now);
            payout.setPaidOutBy(users.getReferenceById(actor.id()));
        } else {
            payout.setPaidOutAt(null);
            payout.setPaidOutBy(null);
        }
        refreshContestSettlement(contest);

        auditService.record(AuditEntry.builder()
                .action(AuditAction.PAYOUT_STATUS_CHANGED)
                .entityType("payout").entityId(payout.getId())
                .contestId(contestId)
                .actorId(actor.id()).actorType(actorType)
                .reason(reason)
                .before(Map.of("payoutStatus", current.name()))
                .after(Map.of("payoutStatus", target.name(), "contestStatus", contest.getContestStatus().name()))
                .build());

        if (target == PayoutStatus.PAID_OUT) {
            notificationService.notifyNow(payout.getUser().getId(), NotificationType.RESULT_DECLARED,
                    ReferenceType.CONTEST, contestId,
                    "You have been paid",
                    "The organizer marked your " + payout.getPayoutType().name().toLowerCase()
                            + " of " + payout.getAmount().toPlainString() + " as paid.");
        }
        return toResponse(payout, contestId, actor.id(), true);
    }

    // ---------- helpers ----------

    private Payout newPayout(Contest contest, ContestParticipant participant, PayoutType type, BigDecimal amount) {
        Payout payout = new Payout();
        payout.setContest(contest);
        payout.setContestParticipant(participant);
        payout.setUser(participant.getUser());
        payout.setPayoutType(type);
        payout.setAmount(amount);

        boolean organizersOwn = contest.getOrganizer().getId().equals(participant.getUser().getId());
        if (organizersOwn) {
            // The organizer holds the pot, so their own share needs no transfer.
            payout.setPayoutStatus(PayoutStatus.PAID_OUT);
            payout.setPaidOutAt(clock.instant());
            payout.setPaidOutBy(participant.getUser());
        } else {
            payout.setPayoutStatus(PayoutStatus.PENDING);
        }
        return payout;
    }

    private static PayoutStatus parseStatus(String name) {
        if (name == null) {
            throw new BadRequestException("A status is required");
        }
        PayoutStatus status;
        try {
            status = PayoutStatus.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown payout status: " + name);
        }
        if (status == PayoutStatus.VOID) {
            throw new ForbiddenException("Only a result correction can void a payout");
        }
        return status;
    }

    private static PayoutResponse toResponse(Payout p, Long contestId, Long actorId, boolean showUpi) {
        User user = p.getUser();
        return new PayoutResponse(
                p.getId(), contestId, user.getId(), user.getDisplayName(),
                showUpi ? user.getUpiId() : null,
                p.getPayoutType().name(), p.getAmount(), p.getPayoutStatus().name(), p.getPaidOutAt(),
                user.getId().equals(actorId));
    }
}
