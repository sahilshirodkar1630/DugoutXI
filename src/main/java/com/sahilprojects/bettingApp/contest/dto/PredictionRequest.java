package com.sahilprojects.bettingApp.contest.dto;

import jakarta.validation.constraints.NotNull;

/** The team the caller thinks will win. It must be one of the two teams playing. */
public record PredictionRequest(@NotNull(message = "Pick a team") Long teamId) {
}
