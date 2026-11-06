package com.sahilprojects.bettingApp.contest.dto;

import jakarta.validation.constraints.Size;

/**
 * paid = true accepts someone who has already sent the money, which confirms them in one step.
 * paid = false (or missing) accepts them as unpaid, and they must pay before the payment deadline.
 * reason is only needed when an admin is acting in the organizer's place.
 */
public record AcceptParticipantRequest(Boolean paid, @Size(max = 500) String reason) {
}
