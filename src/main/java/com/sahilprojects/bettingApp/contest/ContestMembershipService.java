package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.ForbiddenException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.contest.dto.AcceptParticipantRequest;
import com.sahilprojects.bettingApp.contest.dto.ParticipantResponse;
import com.sahilprojects.bettingApp.contest.dto.ReverseParticipantRequest;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.participation.ParticipationActor;
import com.sahilprojects.bettingApp.participation.ParticipationService;
import com.sahilprojects.bettingApp.participation.ReverseTarget;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Joining somebody else's standalone contest. The league equivalent is LeagueMembershipService, and the two share
 * the same status workflow through ParticipationService, so accepting, rejecting, removing and reversing behave
 * the same way in both places.
 *
 * The difference is the money. A league member pays once for the whole league; a standalone participant pays for
 * that one contest, so each participant row carries its own payment status and the organizer confirms it.
 *
 * Nothing here can be undone after the contest starts. Once it has, the entries are in the pool and the result is
 * about to be worked out from them, so a place in the contest is fixed.
 */
@Service
public class ContestMembershipService {

    private final ContestRepository contests;
    private final ContestParticipantRepository participants;
    private final UserRepository users;
    private final ParticipationService participation;
    private final AccessGuard accessGuard;
    private final NotificationService notificationService;
    private final Clock clock;

    public ContestMembershipService(ContestRepository contests, ContestParticipantRepository participants,
                                    UserRepository users, ParticipationService participation,
                                    AccessGuard accessGuard, NotificationService notificationService, Clock clock) {
        this.contests = contests;
        this.participants = participants;
        this.users = users;
        this.participation = participation;
        this.accessGuard = accessGuard;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---------- the user ----------

    /**
     * Asks the organizer for a place. Standalone contests only, while the contest is still SCHEDULED and before its
     * registration deadline. Someone who was rejected or who withdrew may ask again.
     */
    @Transactional
    public ParticipantResponse requestToJoin(AuthenticatedUser actor, Long contestId) {
        Contest contest = lockStandalone(contestId);
        requireOpenForJoining(contest);

        Optional<ContestParticipant> existing = participants.findByContest_IdAndUser_Id(contestId, actor.id());
        if (existing.isPresent()) {
            ContestParticipant row = existing.get();
            ParticipantStatus status = row.getParticipantStatus();
            if (status == ParticipantStatus.REJECTED || status == ParticipantStatus.CANCELLED) {
                participation.requestAgain(row);
                participants.saveAndFlush(row);
                notifyOrganizer(contest, actor, "asked to join again");
                return toResponse(row, contest, false, actor.id());
            }
            throw new ConflictException("You already have a place in this contest (status " + status + ")");
        }

        ContestParticipant row = new ContestParticipant();
        row.setContest(contest);
        row.setUser(users.getReferenceById(actor.id()));
        row.setParticipantStatus(ParticipantStatus.PENDING_APPROVAL);
        row.setPaymentStatus(ContestPaymentStatus.PENDING);
        participants.saveAndFlush(row);
        notifyOrganizer(contest, actor, "asked to join");
        return toResponse(row, contest, false, actor.id());
    }

    /** Withdraws a request that has not been confirmed. A confirmed participant must ask the organizer. */
    @Transactional
    public ParticipantResponse withdraw(AuthenticatedUser actor, Long contestId) {
        Contest contest = lockStandalone(contestId);
        ContestParticipant row = participants.findByContest_IdAndUser_Id(contestId, actor.id())
                .orElseThrow(() -> new NotFoundException("You have no request in this contest"));
        if (contest.getOrganizer().getId().equals(actor.id())) {
            throw new ConflictException("The organizer cannot leave their own contest. Cancel the contest instead");
        }
        if (row.getParticipantStatus() == ParticipantStatus.CONFIRMED) {
            throw new ConflictException("You are already confirmed. Ask the organizer to reverse your place");
        }
        participation.cancelRequest(row);
        participants.saveAndFlush(row);
        return toResponse(row, contest, false, actor.id());
    }

    // ---------- reading ----------

    /**
     * The organizer and admins see everyone, with the contact details they need in order to pay.
     * Anyone else sees the confirmed participants only, and no contact details.
     */
    @Transactional(readOnly = true)
    public List<ParticipantResponse> list(AuthenticatedUser actor, Long contestId, ParticipantStatus statusFilter) {
        Contest contest = contests.findByIdWithDetails(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        if (contest.getLeague() != null) {
            accessGuard.requireLeagueMember(actor, contest.getLeague());
        }
        boolean privileged = contest.getOrganizer().getId().equals(actor.id()) || actor.isAdmin();
        return participants.findByContestWithUser(contestId).stream()
                .filter(p -> privileged || p.getParticipantStatus() == ParticipantStatus.CONFIRMED
                        || p.getUser().getId().equals(actor.id()))
                .filter(p -> statusFilter == null || p.getParticipantStatus() == statusFilter)
                .map(p -> toResponse(p, contest, privileged, actor.id()))
                .toList();
    }

    // ---------- the organizer (or an admin with a reason) ----------

    /** Accepts a request, either as already paid or as still owing. */
    @Transactional
    public ParticipantResponse accept(AuthenticatedUser actor, Long contestId, Long participantId,
                                      AcceptParticipantRequest request) {
        Contest contest = lockStandalone(contestId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, contest, request.reason());
        requireOpenForJoining(contest);
        ContestParticipant row = loadParticipant(contest, participantId);
        requireNotOrganizersOwnRow(contest, row);

        ParticipationActor who = actorFor(actor, actingAs, request.reason());
        if (Boolean.TRUE.equals(request.paid())) {
            participation.acceptPaid(row, who);
        } else {
            requirePaymentDeadlineInFuture(contest,
                    "so a participant cannot be accepted as unpaid. Accept them as paid, or move the deadline");
            participation.acceptUnpaid(row, who);
        }
        participants.saveAndFlush(row);
        notifyDecision(contest, row, true);
        refreshReminders(contest);
        return toResponse(row, contest, true, actor.id());
    }

    /** Records that an accepted participant has now sent the money, which confirms their place. */
    @Transactional
    public ParticipantResponse confirmPayment(AuthenticatedUser actor, Long contestId, Long participantId,
                                              String reason) {
        Contest contest = lockStandalone(contestId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, contest, reason);
        requireOpenForJoining(contest);
        ContestParticipant row = loadParticipant(contest, participantId);
        requireNotOrganizersOwnRow(contest, row);
        requirePaymentDeadlineInFuture(contest, "Move it before marking this payment received");

        participation.confirmPayment(row, actorFor(actor, actingAs, reason));
        participants.saveAndFlush(row);
        notificationService.notifyNow(row.getUser().getId(), NotificationType.JOIN_REQUEST_DECISION,
                ReferenceType.CONTEST, contestId,
                "You are in: " + label(contest),
                "The organizer confirmed your payment. Make your pick before the contest starts.");
        refreshReminders(contest);
        return toResponse(row, contest, true, actor.id());
    }

    @Transactional
    public ParticipantResponse reject(AuthenticatedUser actor, Long contestId, Long participantId, String reason) {
        Contest contest = lockStandalone(contestId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, contest, reason);
        requireOpenForJoining(contest);
        ContestParticipant row = loadParticipant(contest, participantId);
        requireNotOrganizersOwnRow(contest, row);

        participation.reject(row, actorFor(actor, actingAs, reason), reason);
        participants.saveAndFlush(row);
        notifyDecision(contest, row, false);
        return toResponse(row, contest, true, actor.id());
    }

    /** Removes an accepted participant who never paid. A confirmed one is reversed instead. */
    @Transactional
    public ParticipantResponse remove(AuthenticatedUser actor, Long contestId, Long participantId, String reason) {
        Contest contest = lockStandalone(contestId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, contest, reason);
        requireNotStarted(contest, "remove participants from");
        ContestParticipant row = loadParticipant(contest, participantId);
        requireNotOrganizersOwnRow(contest, row);

        participation.remove(row, actorFor(actor, actingAs, reason), reason);
        participants.saveAndFlush(row);
        return toResponse(row, contest, true, actor.id());
    }

    /**
     * Undoes a confirmed place, because the entry money was handed back outside the app.
     * Only before the contest starts: afterwards the entry is already part of the pool.
     */
    @Transactional
    public ParticipantResponse reverse(AuthenticatedUser actor, Long contestId, Long participantId,
                                       ReverseParticipantRequest request) {
        Contest contest = lockStandalone(contestId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, contest, request.reason());
        requireNotStarted(contest, "reverse participants in");
        ContestParticipant row = loadParticipant(contest, participantId);
        requireNotOrganizersOwnRow(contest, row);

        if (request.target() == ReverseTarget.ACCEPTED_UNPAID) {
            requirePaymentDeadlineInFuture(contest,
                    "so a participant cannot go back to unpaid. Remove them instead, or move the deadline");
        }
        participation.reverseConfirmed(row, actorFor(actor, actingAs, request.reason()),
                request.reason(), request.target(), false);
        participants.saveAndFlush(row);
        notificationService.notifyNow(row.getUser().getId(), NotificationType.JOIN_REQUEST_DECISION,
                ReferenceType.CONTEST, contestId,
                "Your place in " + label(contest) + " has changed",
                request.reason());
        return toResponse(row, contest, true, actor.id());
    }

    // ---------- rules ----------

    private Contest lockStandalone(Long contestId) {
        Contest contest = contests.findByIdForUpdate(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        if (!contest.isStandalone()) {
            throw new ConflictException(
                    "This contest belongs to a league. Joining is handled by the league's membership");
        }
        // findByIdForUpdate does not fetch the associations, so touch the ones the response needs.
        contest.getTeamA().getShortCode();
        contest.getTeamB().getShortCode();
        contest.getOrganizer().getId();
        return contest;
    }

    private void requireOpenForJoining(Contest contest) {
        requireNotStarted(contest, "join");
        Instant now = clock.instant();
        if (contest.getRegistrationDeadline() != null && now.isAfter(contest.getRegistrationDeadline())) {
            throw new ConflictException("The registration deadline for this contest has passed");
        }
    }

    private void requireNotStarted(Contest contest, String what) {
        if (contest.getContestStatus() != ContestStatus.SCHEDULED) {
            throw new ConflictException("This contest is " + contest.getContestStatus()
                    + ", so it is too late to " + what + " it");
        }
        if (!clock.instant().isBefore(contest.getStartTime())) {
            throw new ConflictException("This contest has started, so it is too late to " + what + " it");
        }
    }

    private void requirePaymentDeadlineInFuture(Contest contest, String trailer) {
        Instant deadline = contest.getPaymentDeadline();
        if (deadline != null && !deadline.isAfter(clock.instant())) {
            throw new BadRequestException("The payment deadline has passed, " + trailer);
        }
    }

    private ContestParticipant loadParticipant(Contest contest, Long participantId) {
        return participants.findById(participantId)
                .filter(p -> p.getContest().getId().equals(contest.getId()))
                .orElseThrow(() -> new NotFoundException("Participant not found in this contest"));
    }

    private static void requireNotOrganizersOwnRow(Contest contest, ContestParticipant row) {
        if (contest.getOrganizer().getId().equals(row.getUser().getId())) {
            throw new ForbiddenException("The organizer's own place in the contest cannot be changed");
        }
    }

    private static ParticipationActor actorFor(AuthenticatedUser actor, ActorType actingAs, String reason) {
        return actingAs == ActorType.ADMIN
                ? ParticipationActor.admin(actor.id(), reason)
                : ParticipationActor.user(actor.id());
    }

    // ---------- plumbing ----------

    private void notifyOrganizer(Contest contest, AuthenticatedUser actor, String what) {
        notificationService.notifyNow(contest.getOrganizer().getId(), NotificationType.JOIN_REQUEST_RECEIVED,
                ReferenceType.CONTEST, contest.getId(),
                "Someone " + what + " " + label(contest),
                "Open the contest to accept or reject the request.");
    }

    private void notifyDecision(Contest contest, ContestParticipant row, boolean accepted) {
        String title = accepted
                ? "You were accepted into " + label(contest)
                : "Your request for " + label(contest) + " was declined";
        String message = accepted
                ? (row.getParticipantStatus() == ParticipantStatus.CONFIRMED
                ? "Your place is confirmed. Make your pick before the contest starts."
                : "Send the entry amount to the organizer before the payment deadline.")
                : row.getStatusReason();
        notificationService.notifyNow(row.getUser().getId(), NotificationType.JOIN_REQUEST_DECISION,
                ReferenceType.CONTEST, contest.getId(), title, message);
    }

    /** A new participant needs the same nudges as everyone else, so the contest's reminders are rebuilt. */
    private void refreshReminders(Contest contest) {
        List<Long> interested = new java.util.ArrayList<>();
        for (ContestParticipant row : participants.findByContestWithUser(contest.getId())) {
            switch (row.getParticipantStatus()) {
                case CONFIRMED, ACCEPTED_UNPAID, PENDING_APPROVAL -> interested.add(row.getUser().getId());
                default -> {
                    // no longer their contest
                }
            }
        }
        notificationService.rebuildContestReminders(contest.getId(), label(contest), contest.getStartTime(), interested);
    }

    private static String label(Contest contest) {
        if (contest.getName() != null && !contest.getName().isBlank()) {
            return contest.getName();
        }
        return contest.getTeamA().getShortCode() + " vs " + contest.getTeamB().getShortCode();
    }

    private static ParticipantResponse toResponse(ContestParticipant row, Contest contest,
                                                  boolean privileged, Long actorId) {
        User user = row.getUser();
        boolean mine = user.getId().equals(actorId);
        boolean showDetails = privileged || mine;
        return new ParticipantResponse(
                row.getId(), contest.getId(), user.getId(), user.getDisplayName(),
                showDetails ? user.getPhoneNumber() : null,
                showDetails ? user.getUpiId() : null,
                row.getParticipantStatus().name(),
                row.getPaymentStatus().name(),
                row.getRequestedAt(), row.getConfirmedAt(), row.getRemovedAt(),
                showDetails ? row.getStatusReason() : null,
                contest.getOrganizer().getId().equals(user.getId()),
                mine);
    }
}
