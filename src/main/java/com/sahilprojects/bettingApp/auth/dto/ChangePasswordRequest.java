package com.sahilprojects.bettingApp.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The current password proves it is really you; 72 is where BCrypt stops reading. */
public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
