package com.sahilprojects.bettingApp.dashboard.dto;

import java.math.BigDecimal;

/**
 * One user's record across every league and standalone contest they have played.
 * Refunded contests are not counted as played: nothing was won or lost in them.
 */
public record MyStatsResponse(
        long userId,
        String displayName,
        int leaguesJoined,
        int contestsPlayed,
        int contestsWon,
        int contestsLost,
        int contestsRefunded,
        BigDecimal amountWon,
        BigDecimal amountLost,
        BigDecimal netPl,
        BigDecimal winPercentage) {
}
