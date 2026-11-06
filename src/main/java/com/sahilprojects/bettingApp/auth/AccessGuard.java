package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ForbiddenException;
import com.sahilprojects.bettingApp.contest.Contest;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.league.LeagueMemberRepository;
import com.sahilprojects.bettingApp.league.LeagueMemberStatus;
import org.springframework.stereotype.Component;

/**
 * All ownership and permission checks live here, not in controllers.
 * There is no ORGANIZER role. Whoever creates a league or contest is its organizer, checked per entity.
 */
@Component
public class AccessGuard {

    private final LeagueMemberRepository members;

    public AccessGuard(LeagueMemberRepository members) {
        this.members = members;
    }

    /** Organizer only. An admin is not enough. */
    public void requireOrganizer(AuthenticatedUser actor, League league) {
        if (!actor.id().equals(league.getOrganizer().getId())) {
            throw new ForbiddenException("Only the league organizer can do this");
        }
    }

    public void requireOrganizer(AuthenticatedUser actor, Contest contest) {
        if (!actor.id().equals(contest.getOrganizer().getId())) {
            throw new ForbiddenException("Only the contest organizer can do this");
        }
    }

    /**
     * The organizer, or an admin acting with a written reason. Returns how the caller is acting,
     * so the audit entry can record USER or ADMIN.
     */
    public ActorType requireOrganizerOrAdmin(AuthenticatedUser actor, League league, String adminReason) {
        return organizerOrAdmin(actor, league.getOrganizer().getId(), adminReason);
    }

    public ActorType requireOrganizerOrAdmin(AuthenticatedUser actor, Contest contest, String adminReason) {
        return organizerOrAdmin(actor, contest.getOrganizer().getId(), adminReason);
    }

    /** Admin only, no reason needed (for routine reference-data maintenance). */
    public void requireAdmin(AuthenticatedUser actor) {
        if (!actor.isAdmin()) {
            throw new ForbiddenException("Only an admin can do this");
        }
    }

    /** Admin overrides need a reason. A blank reason is refused. */
    public void requireAdminWithReason(AuthenticatedUser actor, String reason) {
        if (!actor.isAdmin()) {
            throw new ForbiddenException("Only an admin can do this");
        }
        requireReason(reason);
    }

    /** Admin, the organizer, or a CONFIRMED member of the league. */
    public void requireLeagueMember(AuthenticatedUser actor, League league) {
        if (actor.isAdmin() || actor.id().equals(league.getOrganizer().getId())) {
            return;
        }
        boolean confirmed = members.findByLeague_IdAndUser_Id(league.getId(), actor.id())
                .filter(m -> m.getLeagueMemberStatus() == LeagueMemberStatus.CONFIRMED)
                .isPresent();
        if (!confirmed) {
            throw new ForbiddenException("Only members of this league can see this");
        }
    }

    private ActorType organizerOrAdmin(AuthenticatedUser actor, Long organizerId, String adminReason) {
        if (actor.id().equals(organizerId)) {
            return ActorType.USER;
        }
        if (actor.isAdmin()) {
            requireReason(adminReason);
            return ActorType.ADMIN;
        }
        throw new ForbiddenException("Only the organizer can do this");
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required for this action");
        }
    }
}
