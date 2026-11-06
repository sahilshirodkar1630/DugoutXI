package com.sahilprojects.bettingApp.dashboard.dto;

import java.math.BigDecimal;

/** One line of the league table. */
public record LeagueBoardRowResponse(
        int rank,
        long userId,
        String displayName,
        int wonCount,
        int lostCount,
        int refundedCount,
        BigDecimal winnings,
        BigDecimal entriesSpent,
        BigDecimal netPl,
        BigDecimal joiningAmountPaid,
        BigDecimal runningBalance,
        String settlementStatus,
        boolean me) {
}
