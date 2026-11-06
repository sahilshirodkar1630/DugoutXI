package com.sahilprojects.bettingApp.league;

import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.league.dto.*;
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
 * The join flow for a league: request, withdraw, and the organizer's accept, confirm payment, reject, remove and reverse.
 *
 * This service owns the league-specific rules (league must be OPEN, deadlines, who may act, the organizer's own row).
 * The status moves themselves, their audit rows and the "gained or lost CONFIRMED" event come from ParticipationService.
 * Every action takes the league row lock first, so it cannot interleave with an edit or with the league starting.
 */
@Service
public class LeagueMembershipService {

    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final UserRepository users;
    private final ParticipationService participation;
    private final AccessGuard accessGuard;
    private final NotificationService notificationService;
    private final Clock clock;

    public LeagueMembershipService(LeagueRepository leagues, LeagueMemberRepository members, UserRepository users,
                                   ParticipationService participation, AccessGuard accessGuard,
                                   NotificationService notificationService, Clock clock) {
        this.leagues = leagues;
        this.members = members;
        this.users = users;
        this.participation = participation;
        this.accessGuard = accessGuard;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---------- the user ----------

    /**
     * Asks to join. Only while the league is OPEN and before the registration deadline.
     * A rejected or withdrawn user can ask again. A removed user cannot, and a confirmed organizer already has a row.
     */
    @Transactional
    public MemberResponse requestToJoin(AuthenticatedUser actor, Long leagueId) {
        League league = lockLeague(leagueId);
        requireOpen(league, "join");
        if (clock.instant().isAfter(league.getRegistrationDeadline())) {
            throw new ConflictException("The registration deadline has passed");
        }

        Optional<LeagueMember> existing = members.findByLeague_IdAndUser_Id(leagueId, actor.id());
        if (existing.isPresent()) {
            LeagueMember member = existing.get();
            LeagueMemberStatus status = member.getLeagueMemberStatus();
            if (status == LeagueMemberStatus.REJECTED || status == LeagueMemberStatus.CANCELLED) {
                participation.requestAgain(member);
                members.saveAndFlush(member);
                tellOrganizer(league, member, "asked to join again");
                return toResponse(member, false, league);
            }
            throw new ConflictException("You already have a place in this league (status " + status + ")");
        }

        LeagueMember member = new LeagueMember();
        member.setLeague(league);
        member.setUser(users.getReferenceById(actor.id()));
        member.setLeagueMemberStatus(LeagueMemberStatus.PENDING_APPROVAL);
        member.setPaymentStatus(PaymentStatus.PENDING);
        members.saveAndFlush(member);
        tellOrganizer(league, member, "asked to join");
        return toResponse(member, false, league);
    }

    /** Withdraws a request that is not confirmed yet. A confirmed member cannot withdraw. */
    @Transactional
    public MemberResponse withdraw(AuthenticatedUser actor, Long leagueId) {
        League league = lockLeague(leagueId);
        LeagueMember member = members.findByLeague_IdAndUser_Id(leagueId, actor.id())
                .orElseThrow(() -> new NotFoundException("You have no request in this league"));
        if (member.getLeagueMemberStatus() == LeagueMemberStatus.CONFIRMED) {
            throw new ConflictException("A confirmed member cannot withdraw. Ask the organizer to reverse your membership");
        }
        participation.cancelRequest(member);
        members.saveAndFlush(member);
        return toResponse(member, false, league);
    }

    // ---------- reading ----------

    /**
     * The organizer and admins see everyone, including pending requests and contact details.
     * A confirmed member sees the confirmed members and their payment status, without contact details.
     */
    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(AuthenticatedUser actor, Long leagueId, LeagueMemberStatus statusFilter) {
        League league = leagues.findById(leagueId).orElseThrow(() -> new NotFoundException("League not found"));
        accessGuard.requireLeagueMember(actor, league);
        boolean privileged = isPrivileged(actor, league);
        return members.findAllWithUser(leagueId).stream()
                .filter(m -> privileged || m.getLeagueMemberStatus() == LeagueMemberStatus.CONFIRMED)
                .filter(m -> statusFilter == null || m.getLeagueMemberStatus() == statusFilter)
                .map(m -> toResponse(m, privileged, league))
                .toList();
    }

    // ---------- the organizer (or an admin with a reason) ----------

    @Transactional
    public MemberResponse accept(AuthenticatedUser actor, Long leagueId, Long memberId, AcceptMemberRequest request) {
        League league = lockLeague(leagueId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, request.reason());
        requireOpen(league, "accept members into");
        LeagueMember member = loadMember(league, memberId);
        requireNotOrganizerRow(league, member);

        ParticipationActor who = actorFor(actor, actingAs, request.reason());
        if (Boolean.TRUE.equals(request.paid())) {
            participation.acceptPaid(member, who);
        } else {
            if (!league.getPaymentDeadline().isAfter(clock.instant())) {
                throw new BadRequestException("The payment deadline has passed, so a member cannot be accepted as unpaid. "
                        + "Accept them as paid, or extend the payment deadline first");
            }
            participation.acceptUnpaid(member, who);
        }
        members.saveAndFlush(member);
        tellMember(league, member, "You were accepted into " + league.getName(),
                member.getLeagueMemberStatus() == LeagueMemberStatus.CONFIRMED
                        ? "Your place is confirmed. Make your picks before each contest starts."
                        : "Pay the buy-in of " + league.getAmountPerContest().multiply(java.math.BigDecimal.valueOf(league.getTotalContests())).toPlainString()
                        + " to the organizer before " + league.getPaymentDeadline() + " to keep your place.");
        return toResponse(member, true, league);
    }

    /** The organizer marks the payment of an accepted member as received. Needs the payment deadline to be in the future. */
    @Transactional
    public MemberResponse confirmPayment(AuthenticatedUser actor, Long leagueId, Long memberId, String reason) {
        League league = lockLeague(leagueId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, reason);
        requireOpen(league, "confirm payments in");
        LeagueMember member = loadMember(league, memberId);
        requireNotOrganizerRow(league, member);
        if (!league.getPaymentDeadline().isAfter(clock.instant())) {
            throw new BadRequestException("The payment deadline has passed. Extend it before marking this payment received");
        }
        participation.confirmPayment(member, actorFor(actor, actingAs, reason));
        members.saveAndFlush(member);
        tellMember(league, member, "You are in: " + league.getName(),
                "The organizer confirmed your payment. Make your picks before each contest starts.");
        return toResponse(member, true, league);
    }

    @Transactional
    public MemberResponse reject(AuthenticatedUser actor, Long leagueId, Long memberId, String reason) {
        League league = lockLeague(leagueId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, reason);
        requireOpen(league, "reject members of");
        LeagueMember member = loadMember(league, memberId);
        requireNotOrganizerRow(league, member);
        participation.reject(member, actorFor(actor, actingAs, reason), reason);
        members.saveAndFlush(member);
        tellMember(league, member, "Your request for " + league.getName() + " was declined", member.getStatusReason());
        return toResponse(member, true, league);
    }

    /** Removes an accepted member who has not paid. A confirmed member is reversed, not removed. */
    @Transactional
    public MemberResponse remove(AuthenticatedUser actor, Long leagueId, Long memberId, String reason) {
        League league = lockLeague(leagueId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, reason);
        requireOpen(league, "remove members from");
        LeagueMember member = loadMember(league, memberId);
        requireNotOrganizerRow(league, member);
        participation.remove(member, actorFor(actor, actingAs, reason), reason);
        members.saveAndFlush(member);
        tellMember(league, member, "You were removed from " + league.getName(), member.getStatusReason());
        return toResponse(member, true, league);
    }

    /**
     * Reverses a CONFIRMED member.
     *  - While the league is OPEN: the organizer (or an admin) can send the member back to ACCEPTED_UNPAID or remove them.
     *    The money is returned outside the app, so the payment goes back to PENDING and no settlement row will exist.
     *  - Once the league is ACTIVE: admin only, REMOVED only. The money stays and comes back through settlement.
     */
    @Transactional
    public MemberResponse reverse(AuthenticatedUser actor, Long leagueId, Long memberId, ReverseMemberRequest request) {
        League league = lockLeague(leagueId);
        LeagueMember member = loadMember(league, memberId);
        requireNotOrganizerRow(league, member);

        if (league.getStatus() == LeagueStatus.OPEN) {
            ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, request.reason());
            participation.reverseConfirmed(member, actorFor(actor, actingAs, request.reason()),
                    request.reason(), request.target(), false);
        } else if (league.getStatus() == LeagueStatus.ACTIVE) {
            accessGuard.requireAdminWithReason(actor, request.reason());
            if (request.target() != ReverseTarget.REMOVED) {
                throw new BadRequestException("After the league has started a member can only be removed, not sent back to unpaid");
            }
            participation.reverseConfirmed(member, ParticipationActor.admin(actor.id(), request.reason()),
                    request.reason(), ReverseTarget.REMOVED, true);
        } else {
            throw new ConflictException("Members cannot be reversed while the league is " + league.getStatus());
        }
        members.saveAndFlush(member);
        tellMember(league, member, "Your place in " + league.getName() + " has changed", request.reason());
        return toResponse(member, true, league);
    }

    // ---------- notifications ----------

    private void tellOrganizer(League league, LeagueMember member, String what) {
        String who = users.findById(member.getUser().getId()).map(User::getDisplayName).orElse("Someone");
        notificationService.notifyNow(league.getOrganizer().getId(), NotificationType.JOIN_REQUEST_RECEIVED,
                ReferenceType.LEAGUE, league.getId(),
                who + " " + what + " " + league.getName(),
                "Open the league's players to accept or decline the request.");
    }

    private void tellMember(League league, LeagueMember member, String title, String message) {
        notificationService.notifyNow(member.getUser().getId(), NotificationType.JOIN_REQUEST_DECISION,
                ReferenceType.LEAGUE, league.getId(), title, message);
    }

    // ---------- helpers ----------

    private League lockLeague(Long id) {
        return leagues.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("League not found"));
    }

    private LeagueMember loadMember(League league, Long memberId) {
        LeagueMember member = members.findById(memberId).orElseThrow(() -> new NotFoundException("Member not found"));
        if (!member.getLeague().getId().equals(league.getId())) {
            throw new NotFoundException("Member not found in this league");
        }
        return member;
    }

    private static void requireOpen(League league, String action) {
        if (league.getStatus() == LeagueStatus.OPEN) {
            return;
        }
        if (league.getStatus() == LeagueStatus.UPCOMING) {
            throw new ConflictException("Joining has not opened yet, so you cannot " + action + " this league");
        }
        throw new ConflictException("Joining is closed (the league is " + league.getStatus() + "), so you cannot " + action + " it");
    }

    private static void requireNotOrganizerRow(League league, LeagueMember member) {
        if (member.getUser().getId().equals(league.getOrganizer().getId())) {
            throw new ConflictException("The organizer's own membership cannot be changed");
        }
    }

    private static ParticipationActor actorFor(AuthenticatedUser actor, ActorType actingAs, String reason) {
        return actingAs == ActorType.ADMIN
                ? ParticipationActor.admin(actor.id(), reason)
                : ParticipationActor.user(actor.id());
    }

    private static boolean isPrivileged(AuthenticatedUser actor, League league) {
        return actor.isAdmin() || actor.id().equals(league.getOrganizer().getId());
    }

    private static MemberResponse toResponse(LeagueMember m, boolean privileged, League league) {
        User user = m.getUser();
        return new MemberResponse(
                m.getId(), user.getId(), user.getDisplayName(),
                privileged ? user.getPhoneNumber() : null,
                privileged ? user.getUpiId() : null,
                m.currentStatus(), m.getPaymentStatus().name(),
                m.getPaidAt(), m.getRequestedAt(), m.getConfirmedAt(), m.getRemovedAt(),
                privileged ? m.getStatusReason() : null,
                user.getId().equals(league.getOrganizer().getId()));
    }
}
