package com.sahilprojects.bettingApp.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

/** The home screen: the caller's record, what they are owed, what they owe, and anything waiting on them. */
public record MySummaryResponse(
        MyStatsResponse stats,
        int unreadNotifications,
        BigDecimal pendingPayoutTotal,
        List<PendingItem> pendingPayouts,
        BigDecimal pendingSettlementTotal,
        List<PendingItem> pendingSettlements,
        List<PendingItem> awaitingMyAction) {

    /**
     * One thing still open. amount is what is at stake; owedToMe says which way it goes, so the frontend can show
     * "you are owed" or "you owe" without working it out again.
     */
    public record PendingItem(
            String kind,
            long referenceId,
            String label,
            BigDecimal amount,
            boolean owedToMe,
            String status) {
    }
}
