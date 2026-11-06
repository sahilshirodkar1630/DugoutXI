package com.sahilprojects.bettingApp.contest.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One request for both kinds of contest.
 *
 * League contest: send leagueId. The tournament, amount and deadlines come from the league, so tournamentId, amount,
 * registrationDeadline, paymentDeadline and paymentInstructions must be left out. contestNumber is optional
 * (the lowest free number is used when it is missing).
 *
 * Standalone contest: leave leagueId out and send tournamentId, amount, registrationDeadline and paymentDeadline.
 */
public record CreateContestRequest(
        Long leagueId,
        @NotNull Long teamAId,
        @NotNull Long teamBId,
        @NotNull Instant startTime,
        @Size(max = 100) String name,
        @Min(1) @Max(1000) Integer contestNumber,
        Long tournamentId,
        @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal amount,
        Instant registrationDeadline,
        Instant paymentDeadline,
        @Size(max = 500) String paymentInstructions) {
}
