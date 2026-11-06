package com.sahilprojects.bettingApp.contest.dto;

import java.math.BigDecimal;

/** One participant's outcome: what they picked, how it went, and the money either way. */
public record ParticipantOutcomeResponse(
        long participantId,
        long userId,
        String displayName,
        Long selectedTeamId,
        String selectedTeamShortCode,
        String result,
        BigDecimal entrySpent,
        BigDecimal winnings,
        BigDecimal netPl) {
}
