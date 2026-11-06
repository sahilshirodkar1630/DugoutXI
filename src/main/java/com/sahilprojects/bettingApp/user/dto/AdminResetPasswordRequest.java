package com.sahilprojects.bettingApp.user.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminResetPasswordRequest(@NotBlank String reason) {
}
