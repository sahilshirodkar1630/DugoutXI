package com.sahilprojects.bettingApp.contest.dto;

import com.sahilprojects.bettingApp.contest.ContestStatus;
import com.sahilprojects.bettingApp.contest.ResultType;

import java.math.BigDecimal;
import java.time.Instant;

/** A row in a list of contests. No predictions. myParticipantStatus is only filled in "my contests". */
public record ContestSummaryResponse(
        Long id,
        Long leagueId,
        String name,
        Integer contestNumber,
        TeamRef teamA,
        TeamRef teamB,
        Instant startTime,
        BigDecimal contestAmount,
        ContestStatus status,
        ResultType resultType,
        Long winningTeamId,
        String myParticipantStatus,
        TeamRef winningTeam,
        long confirmedParticipants) {
}
