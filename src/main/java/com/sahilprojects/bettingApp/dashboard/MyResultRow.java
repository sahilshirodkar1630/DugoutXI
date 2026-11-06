package com.sahilprojects.bettingApp.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One contest the user took part in, with its outcome for them. result is null while the contest is still waiting
 * for its result. leagueId and leagueName are null for a one-off contest.
 */
public record MyResultRow(
        long contestId,
        Instant startTime,
        Long leagueId,
        String leagueName,
        Integer contestNumber,
        String contestName,
        String teamA,
        String teamB,
        String contestStatus,
        String winner,
        String picked,
        String result,
        BigDecimal entrySpent,
        BigDecimal winningAmount) {

    public boolean decided() {
        return "WON".equals(result) || "LOST".equals(result);
    }

    public BigDecimal net() {
        return winningAmount.subtract(entrySpent);
    }
}
