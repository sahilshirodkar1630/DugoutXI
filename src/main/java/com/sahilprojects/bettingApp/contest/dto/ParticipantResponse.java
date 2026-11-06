package com.sahilprojects.bettingApp.contest.dto;

import java.time.Instant;

/**
 * One participant in a contest. phoneNumber, upiId and statusReason are filled in only for the organizer and
 * admins: the organizer needs them to pay someone, and nobody else does.
 */
public record ParticipantResponse(
        Long participantId,
        Long contestId,
        Long userId,
        String displayName,
        String phoneNumber,
        String upiId,
        String status,
        String paymentStatus,
        Instant requestedAt,
        Instant confirmedAt,
        Instant removedAt,
        String statusReason,
        boolean organizer,
        boolean me) {
}
