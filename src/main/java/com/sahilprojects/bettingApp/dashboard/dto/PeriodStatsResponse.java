package com.sahilprojects.bettingApp.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A user's record over one period (StatsRange), worked out by PeriodStats. Contests are placed in a period by their
 * start time. "Played" means won or lost: refunded contests cost nothing and are counted on their own, and pending
 * ones are still waiting for a result.
 *
 * currentStreak is positive for a run of wins and negative for a run of losses.
 */
public record PeriodStatsResponse(
        String range,
        Instant from,
        Instant to,
        int contestsPlayed,
        int contestsWon,
        int contestsLost,
        int contestsRefunded,
        int contestsPending,
        BigDecimal winPercentage,
        BigDecimal amountInvested,
        BigDecimal amountWon,
        BigDecimal netPl,
        BigDecimal returnPercentage,
        BigDecimal biggestWin,
        int bestStreak,
        int currentStreak,
        int leaguesPlayed,
        int leagueContestsPlayed,
        int oneOffContestsPlayed,
        BigDecimal leagueNetPl,
        BigDecimal oneOffNetPl,
        List<MonthLine> months) {

    /** One calendar month, as "2026-04". */
    public record MonthLine(String month, int played, int won, BigDecimal invested, BigDecimal netPl) {
    }
}
