package com.sahilprojects.bettingApp.contest.dto;

import com.sahilprojects.bettingApp.participation.ReverseTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Undoes a confirmed place in a standalone contest, because the money was returned outside the app.
 * target decides where they land: back to ACCEPTED_UNPAID, or out of the contest altogether.
 */
public record ReverseParticipantRequest(
        @NotBlank(message = "A reason is required") String reason,
        @NotNull(message = "Say where the participant should end up") ReverseTarget target) {
}
