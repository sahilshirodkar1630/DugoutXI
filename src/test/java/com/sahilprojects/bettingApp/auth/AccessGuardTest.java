package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ForbiddenException;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.league.LeagueMember;
import com.sahilprojects.bettingApp.league.LeagueMemberRepository;
import com.sahilprojects.bettingApp.league.LeagueMemberStatus;
import com.sahilprojects.bettingApp.user.Role;
import com.sahilprojects.bettingApp.user.User;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessGuardTest {

    private final LeagueMemberRepository members = mock(LeagueMemberRepository.class);
    private final AccessGuard guard = new AccessGuard(members);

    private static AuthenticatedUser actor(long id, Role role) {
        return new AuthenticatedUser(id, "u" + id + "@x.com", role);
    }

    private static League leagueOrganizedBy(long organizerId) {
        User organizer = new User();
        organizer.setId(organizerId);
        League league = new League();
        league.setId(100L);
        league.setOrganizer(organizer);
        return league;
    }

    @Test
    void organizerPasses() {
        guard.requireOrganizer(actor(1, Role.USER), leagueOrganizedBy(1));
    }

    @Test
    void anotherUserIsRefused() {
        assertThatThrownBy(() -> guard.requireOrganizer(actor(2, Role.USER), leagueOrganizedBy(1)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void adminIsNotEnoughForOrganizerOnlyActions() {
        assertThatThrownBy(() -> guard.requireOrganizer(actor(9, Role.ADMIN), leagueOrganizedBy(1)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void organizerActsAsUserWithoutAReason() {
        assertThat(guard.requireOrganizerOrAdmin(actor(1, Role.USER), leagueOrganizedBy(1), null))
                .isEqualTo(ActorType.USER);
    }

    @Test
    void adminNeedsAReasonToOverride() {
        assertThatThrownBy(() -> guard.requireOrganizerOrAdmin(actor(9, Role.ADMIN), leagueOrganizedBy(1), "  "))
                .isInstanceOf(BadRequestException.class);
        assertThat(guard.requireOrganizerOrAdmin(actor(9, Role.ADMIN), leagueOrganizedBy(1), "Organizer is unreachable"))
                .isEqualTo(ActorType.ADMIN);
    }

    @Test
    void plainUserCannotOverride() {
        assertThatThrownBy(() -> guard.requireOrganizerOrAdmin(actor(2, Role.USER), leagueOrganizedBy(1), "because"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requireAdminWithReasonChecksRoleAndReason() {
        assertThatThrownBy(() -> guard.requireAdminWithReason(actor(2, Role.USER), "reason"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> guard.requireAdminWithReason(actor(9, Role.ADMIN), ""))
                .isInstanceOf(BadRequestException.class);
        guard.requireAdminWithReason(actor(9, Role.ADMIN), "Result was entered wrongly");
    }

    @Test
    void confirmedMemberCanSeeTheLeague() {
        LeagueMember member = new LeagueMember();
        member.setLeagueMemberStatus(LeagueMemberStatus.CONFIRMED);
        when(members.findByLeague_IdAndUser_Id(anyLong(), anyLong())).thenReturn(Optional.of(member));

        guard.requireLeagueMember(actor(2, Role.USER), leagueOrganizedBy(1));
    }

    @Test
    void pendingMemberAndStrangerCannotSeeTheLeague() {
        LeagueMember pending = new LeagueMember();
        pending.setLeagueMemberStatus(LeagueMemberStatus.PENDING_APPROVAL);
        when(members.findByLeague_IdAndUser_Id(100L, 2L)).thenReturn(Optional.of(pending));
        when(members.findByLeague_IdAndUser_Id(100L, 3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireLeagueMember(actor(2, Role.USER), leagueOrganizedBy(1)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> guard.requireLeagueMember(actor(3, Role.USER), leagueOrganizedBy(1)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void organizerAndAdminCanAlwaysSeeTheLeague() {
        guard.requireLeagueMember(actor(1, Role.USER), leagueOrganizedBy(1));
        guard.requireLeagueMember(actor(9, Role.ADMIN), leagueOrganizedBy(1));
    }
}
