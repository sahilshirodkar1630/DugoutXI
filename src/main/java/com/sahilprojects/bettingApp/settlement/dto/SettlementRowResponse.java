package com.sahilprojects.bettingApp.settlement.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One member's closing position in a league.
 *
 * finalBalance is what they walk away with: the joining amount they put in, plus everything they won, minus every
 * entry they spent. Compared with what they paid in, a balance above the joining amount means they are owed money
 * and a balance below it means they owe some.
 */
public record SettlementRowResponse(
        long rowId,
        long leagueId,
        long userId,
        String displayName,
        String phoneNumber,
        String upiId,
        boolean organizer,
        BigDecimal joiningAmountPaid,
        BigDecimal netPl,
        BigDecimal finalBalance,
        String status,
        Instant settledAt,
        String writtenOffReason,
        boolean me) {
}
