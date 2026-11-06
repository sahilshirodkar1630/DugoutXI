package com.sahilprojects.bettingApp.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SetActiveRequest(@NotNull Boolean active, @NotBlank String reason) {
}
