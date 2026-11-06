package com.sahilprojects.bettingApp.league.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * paid = true: the organizer already has the money, so the member is CONFIRMED at once.
 * paid = false: accepted, and the member has until the payment deadline to pay.
 * reason is only needed when an admin acts for the organizer.
 */
public record AcceptMemberRequest(@NotNull Boolean paid, @Size(max = 500) String reason) {
}
