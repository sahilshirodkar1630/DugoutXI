package com.sahilprojects.bettingApp.league.dto;

import java.time.Instant;

/**
 * One league member. phoneNumber, upiId and statusReason are only filled for the organizer and admins:
 * the organizer needs them to pay a member back, other members have no need to see them.
 */
public record MemberResponse(
        Long memberId,
        Long userId,
        String displayName,
        String phoneNumber,
        String upiId,
        String status,
        String paymentStatus,
        Instant paidAt,
        Instant requestedAt,
        Instant confirmedAt,
        Instant removedAt,
        String statusReason,
        boolean organizer) {
}
