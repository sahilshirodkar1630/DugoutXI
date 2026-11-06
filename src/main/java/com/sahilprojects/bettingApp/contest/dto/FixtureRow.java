package com.sahilprojects.bettingApp.contest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** One match in an imported fixture list. Teams are named by short code (MI) or full name (Mumbai Indians). */
public record FixtureRow(
        @NotBlank @Size(max = 100) String teamA,
        @NotBlank @Size(max = 100) String teamB,
        @NotNull Instant startTime,
        @Size(max = 100) String name,
        @Min(1) @Max(1000) Integer contestNumber) {
}
