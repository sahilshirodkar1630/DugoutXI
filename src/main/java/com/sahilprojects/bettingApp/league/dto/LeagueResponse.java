package com.sahilprojects.bettingApp.league.dto;

import com.sahilprojects.bettingApp.league.LeagueStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * joiningAmount is also the most a member can lose, so show it on the join screen.
 * paymentInstructions is only filled for the organizer, admins, and members who have been accepted.
 * inviteCode is only filled for the organizer and admins.
 * myMembershipStatus is null when the caller has no membership row.
 */
public record LeagueResponse(
        Long id,
        String name,
        Long tournamentId,
        String tournamentName,
        Long organizerId,
        String organizerDisplayName,
        BigDecimal amountPerContest,
        Integer totalContests,
        BigDecimal joiningAmount,
        Instant registrationStart,
        Instant registrationDeadline,
        Instant paymentDeadline,
        String paymentInstructions,
        String inviteCode,
        LeagueStatus status,
        Instant startedAt,
        Instant endedAt,
        Instant completedAt,
        long confirmedMembers,
        String myMembershipStatus,
        boolean organizer,
        Integer version,
        long contestsCreated,           // contests that are not cancelled, out of totalContests
        String myPaymentStatus) {
}
