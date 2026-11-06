package com.sahilprojects.bettingApp.user.dto;

import com.sahilprojects.bettingApp.user.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminSetRoleRequest(@NotNull Role role, @NotBlank String reason) {
}
