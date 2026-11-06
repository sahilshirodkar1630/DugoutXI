package com.sahilprojects.bettingApp.dashboard;

import java.math.BigDecimal;

public record UserStatsRow(
        long userId,
        int leaguesJoined,
        int contestsPlayed,
        int contestsWon,
        int contestsLost,
        int contestsRefunded,
        BigDecimal amountWon,
        BigDecimal amountLost,
        BigDecimal netPl) {

    /** Won divided by played, as a percentage with one decimal. Zero when nothing was played. */
    public BigDecimal winPercentage() {
        if (contestsPlayed == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(contestsWon * 100.0 / contestsPlayed)
                .setScale(1, java.math.RoundingMode.HALF_UP);
    }
}
