package com.sahilprojects.bettingApp.settlement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PENDING or SETTLED. A reason is required to put a settled row back to pending. */
public record UpdateSettlementStatusRequest(
        @NotBlank String status,
        @Size(max = 500) String reason) {
}
