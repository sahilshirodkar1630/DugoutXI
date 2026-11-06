package com.sahilprojects.bettingApp.league.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Times are ISO-8601 instants, for example 2026-11-01T10:00:00Z or 2026-11-01T15:30:00+05:30. */
public record CreateLeagueRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull Long tournamentId,
        @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal amountPerContest,
        @NotNull @Min(1) @Max(1000) Integer totalContests,
        @NotNull Instant registrationStart,
        @NotNull Instant registrationDeadline,
        @NotNull Instant paymentDeadline,
        @Size(max = 500) String paymentInstructions) {
}
