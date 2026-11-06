package com.sahilprojects.bettingApp.participation;

import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The status workflow for league members and standalone contest participants, written once.
 *
 * This service does not load or save anything and does not check who may do what. The calling service
 * (LeagueService, ContestService) loads the entity, checks permission with AccessGuard and the parent's own rules
 * (league OPEN, deadlines, league started), calls a method here, and the entity is saved with the caller's transaction.
 * Every method needs an existing transaction (MANDATORY), so the status change, its audit row and the confirmation
 * event succeed or fail together.
 *
 * A new request is created by the caller with status PENDING_APPROVAL. That is not a move, so it is not here.
 */
@Service
public class ParticipationService {

    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ParticipationService(AuditService auditService, ApplicationEventPublisher events, Clock clock) {
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    /** PENDING_APPROVAL to CONFIRMED: the organizer accepted and has already received the money. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acceptPaid(Participation p, ParticipationActor actor) {
        requireStatus(p, ParticipationRules.PENDING_APPROVAL, "accepted");
        Map<String, Object> before = snapshot(p);
        boolean wasConfirmed = isConfirmed(p);
        move(p, ParticipationRules.CONFIRMED);
        Instant now = clock.instant();
        p.recordPaid(now);
        p.setConfirmedAt(now);
        finish(p, AuditAction.MEMBER_ACCEPTED, actor, actor.adminReason(), before, wasConfirmed);
    }

    /** PENDING_APPROVAL to ACCEPTED_UNPAID: accepted, payment still to come before the deadline. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acceptUnpaid(Participation p, ParticipationActor actor) {
        requireStatus(p, ParticipationRules.PENDING_APPROVAL, "accepted");
        Map<String, Object> before = snapshot(p);
        boolean wasConfirmed = isConfirmed(p);
        move(p, ParticipationRules.ACCEPTED_UNPAID);
        finish(p, AuditAction.MEMBER_ACCEPTED, actor, actor.adminReason(), before, wasConfirmed);
    }

    /** ACCEPTED_UNPAID to CONFIRMED: the organizer marks the payment received. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmPayment(Participation p, ParticipationActor actor) {
        requireStatus(p, ParticipationRules.ACCEPTED_UNPAID, "marked as paid");
        Map<String, Object> before = snapshot(p);
        boolean wasConfirmed = isConfirmed(p);
        move(p, ParticipationRules.CONFIRMED);
        Instant now = clock.instant();
        p.recordPaid(now);
        p.setConfirmedAt(now);
        finish(p, AuditAction.PAYMENT_STATUS_CHANGED, actor, actor.adminReason(), before, wasConfirmed);
    }

    /** PENDING_APPROVAL to REJECTED. The reason is optional. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reject(Participation p, ParticipationActor actor, String reason) {
        Map<String, Object> before = snapshot(p);
        boolean wasConfirmed = isConfirmed(p);
        move(p, ParticipationRules.REJECTED);
        p.setStatusReason(clean(reason));
        finish(p, AuditAction.MEMBER_REJECTED, actor, firstNonBlank(reason, actor.adminReason()), before, wasConfirmed);
    }

    /** ACCEPTED_UNPAID to REMOVED by the organizer. A confirmed participation is reversed, not removed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void remove(Participation p, ParticipationActor actor, String reason) {
        requireReason(reason);
        if (ParticipationRules.CONFIRMED.equals(p.currentStatus())) {
            throw new BadRequestException("A confirmed participation has to be reversed, not removed");
        }
        Map<String, Object> before = snapshot(p);
        boolean wasConfirmed = isConfirmed(p);
        move(p, ParticipationRules.REMOVED);
        p.setRemovedAt(clock.instant());
        p.setStatusReason(clean(reason));
        finish(p, AuditAction.MEMBER_REMOVED, actor, reason, before, wasConfirmed);
    }

    /**
     * Reverses a CONFIRMED participation. Always needs a reason.
     *
     * keepPayment tells what happened to the money:
     *  - false: the money was given back outside the app, so the payment goes back to PENDING
     *    (normal before the league or contest has started)
     *  - true: the money stays and comes back through settlement (after the league has started, admin only)
     * Going back to ACCEPTED_UNPAID always means the money was returned, so keepPayment must be false there.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseConfirmed(Participation p, ParticipationActor actor, String reason,
                                 ReverseTarget target, boolean keepPayment) {
        requireReason(reason);
        requireStatus(p, ParticipationRules.CONFIRMED, "reversed");
        if (target == ReverseTarget.ACCEPTED_UNPAID && keepPayment) {
            throw new BadRequestException("Going back to unpaid means the money was returned, so the payment cannot be kept");
        }
        Map<String, Object> before = snapshot(p);
        boolean wasConfirmed = isConfirmed(p);
        move(p, target.name());
        if (!keepPayment) {
            p.recordUnpaid();
        }
        if (target == ReverseTarget.ACCEPTED_UNPAID) {
            p.setConfirmedAt(null);
        } else {
            p.setRemovedAt(clock.instant());
        }
        p.setStatusReason(clean(reason));
        finish(p, AuditAction.MEMBER_REVERSED, actor, reason, before, wasConfirmed);
    }

    /** The user withdraws before being confirmed. A confirmed user cannot withdraw. Not audited: it is the user's own request. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelRequest(Participation p) {
        move(p, ParticipationRules.CANCELLED);
    }

    /** REJECTED or CANCELLED back to PENDING_APPROVAL when the user asks again. The caller checks the registration deadline. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestAgain(Participation p) {
        move(p, ParticipationRules.PENDING_APPROVAL);
        p.setStatusReason(null);
    }

    /** Payment cutoff job: ACCEPTED_UNPAID past the deadline becomes REMOVED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cutoffUnpaid(Participation p) {
        if (!ParticipationRules.ACCEPTED_UNPAID.equals(p.currentStatus())) {
            throw new ConflictException("Only an accepted but unpaid participation can be cut off for non-payment");
        }
        Map<String, Object> before = snapshot(p);
        boolean wasConfirmed = isConfirmed(p);
        move(p, ParticipationRules.REMOVED);
        p.setRemovedAt(clock.instant());
        p.setStatusReason("Payment deadline passed");
        finish(p, AuditAction.MEMBER_REMOVED, ParticipationActor.system(), "Payment deadline passed", before, wasConfirmed);
    }

    // ---------- helpers ----------

    /**
     * The table of allowed moves is about where a status can go, but some actions are only meant for one starting status
     * (the table allows PENDING_APPROVAL to CONFIRMED because "accept as paid" needs it, yet confirming a payment must
     * only work for someone who was already accepted). This makes each action check its own starting status.
     */
    private static void requireStatus(Participation p, String required, String action) {
        if (!required.equals(p.currentStatus())) {
            throw new ConflictException("Only a participation in status " + required + " can be " + action
                    + " (this one is " + p.currentStatus() + ")");
        }
    }

    private void move(Participation p, String to) {
        ParticipationRules.requireMove(p.currentStatus(), to);
        p.changeStatus(to);
    }

    private void finish(Participation p, AuditAction action, ParticipationActor actor, String reason,
                        Map<String, Object> before, boolean wasConfirmed) {
        auditService.record(AuditEntry.builder()
                .action(action)
                .entityType(p.kind().name()).entityId(p.getId())
                .leagueId(p.leagueIdForAudit()).contestId(p.contestIdForAudit())
                .actorId(actor.userId()).actorType(actor.type())
                .reason(reason)
                .before(before).after(snapshot(p))
                .build());

        boolean nowConfirmed = isConfirmed(p);
        if (wasConfirmed != nowConfirmed) {
            events.publishEvent(new ParticipationConfirmationChanged(
                    p.kind(), p.getId(), p.leagueIdForAudit(), p.contestIdForAudit(), p.participantUserId(), nowConfirmed));
        }
    }

    private static boolean isConfirmed(Participation p) {
        return ParticipationRules.CONFIRMED.equals(p.currentStatus());
    }

    private static Map<String, Object> snapshot(Participation p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", p.currentStatus());
        m.put("paid", p.isPaid());
        return m;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required for this action");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second;
    }
}
