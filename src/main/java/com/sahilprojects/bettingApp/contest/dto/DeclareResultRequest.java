package com.sahilprojects.bettingApp.contest.dto;

import jakarta.validation.constraints.Size;

/**
 * The result of a contest.
 *
 * Send winningTeamId for a normal result, or noResult = true for an abandoned match, in which case everyone is
 * refunded and nobody's entry is spent.
 *
 * previewToken is required on confirm. It comes from the preview call and ties the confirmation to exactly the
 * figures the organizer was shown, so a pick that changes in between cannot be settled unseen.
 * reason is required for a correction, and for an admin acting on someone else's contest.
 */
public record DeclareResultRequest(
        Long winningTeamId,
        boolean noResult,
        String previewToken,
        @Size(max = 500) String reason) {
}
