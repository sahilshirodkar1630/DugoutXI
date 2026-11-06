package com.sahilprojects.bettingApp.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminSetActiveRequest(@NotNull Boolean active, @NotBlank String reason) {
}
