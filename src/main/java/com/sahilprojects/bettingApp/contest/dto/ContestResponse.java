package com.sahilprojects.bettingApp.contest.dto;

import com.sahilprojects.bettingApp.contest.ContestStatus;
import com.sahilprojects.bettingApp.contest.ResultType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A contest. It never carries anyone's prediction. Other people's picks are only ever returned by the
 * predictions endpoint, and only after the start time.
 * paymentInstructions (standalone only) is filled for the organizer, admins and accepted participants.
 * winningTeamId is only filled once a result has been declared.
 */
public record ContestResponse(
        Long id,
        Long leagueId,
        String leagueName,
        boolean standalone,
        Long tournamentId,
        String tournamentName,
        String name,
        Integer contestNumber,
        TeamRef teamA,
        TeamRef teamB,
        Instant startTime,
        BigDecimal contestAmount,
        Instant registrationDeadline,
        Instant paymentDeadline,
        String paymentInstructions,
        ContestStatus status,
        ResultType resultType,
        Long winningTeamId,
        long confirmedParticipants,
        String myParticipantStatus,
        boolean organizer,
        String organizerDisplayName,
        Integer version,
        TeamRef winningTeam,            // the same team as winningTeamId, ready to display
        BigDecimal leftoverAmount,
        Instant lockedAt,
        String cancelledReason) {
}
