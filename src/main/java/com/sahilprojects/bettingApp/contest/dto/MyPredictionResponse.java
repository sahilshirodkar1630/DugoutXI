package com.sahilprojects.bettingApp.contest.dto;

import java.time.Instant;

/**
 * The caller's own pick. selectedTeamId is null until they predict.
 * locked is true once the contest has started, after which the pick cannot change.
 */
public record MyPredictionResponse(
        long contestId,
        Long selectedTeamId,
        String selectedTeamName,
        String selectedTeamShortCode,
        Instant predictedAt,
        Instant startTime,
        boolean locked,
        TeamRef teamA,
        TeamRef teamB) {
}
