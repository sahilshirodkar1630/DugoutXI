package com.sahilprojects.bettingApp.payout.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One payout the organizer of a standalone contest owes, or has already handed over. */
public record PayoutResponse(
        long id,
        long contestId,
        long userId,
        String displayName,
        String upiId,
        String type,
        BigDecimal amount,
        String status,
        Instant paidOutAt,
        boolean me) {
}
