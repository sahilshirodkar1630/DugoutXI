package com.sahilprojects.bettingApp.payout.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PENDING or PAID_OUT. A reason is required to take a payout back to PENDING, because that undoes a record of
 * money having been handed over.
 */
public record UpdatePayoutStatusRequest(
        @NotBlank String status,
        @Size(max = 500) String reason) {
}
