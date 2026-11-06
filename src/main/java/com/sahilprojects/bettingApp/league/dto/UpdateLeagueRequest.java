package com.sahilprojects.bettingApp.league.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Replaces the editable fields. The tournament cannot be changed.
 * version must be the version the organizer last saw, so a second tab cannot overwrite a newer change.
 */
public record UpdateLeagueRequest(
        @NotNull Integer version,
        @NotBlank @Size(max = 100) String name,
        @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal amountPerContest,
        @NotNull @Min(1) @Max(1000) Integer totalContests,
        @NotNull Instant registrationStart,
        @NotNull Instant registrationDeadline,
        @NotNull Instant paymentDeadline,
        @Size(max = 500) String paymentInstructions) {
}
