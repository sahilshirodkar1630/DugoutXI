package com.sahilprojects.bettingApp.dashboard;

import java.math.BigDecimal;

/**
 * One member on the live league board. Worked out on every request from the contests played so far, never stored.
 *
 * runningBalance is where they stand right now: the joining amount they paid, plus everything they have won, minus
 * every entry they have spent. settlementStatus stays null until the league ends and the balances are frozen.
 */
public record LeagueBoardRow(
        long userId,
        String displayName,
        int rank,
        int wonCount,
        int lostCount,
        int refundedCount,
        BigDecimal winnings,
        BigDecimal entriesSpent,
        BigDecimal netPl,
        BigDecimal joiningAmountPaid,
        BigDecimal runningBalance,
        String settlementStatus) {
}
