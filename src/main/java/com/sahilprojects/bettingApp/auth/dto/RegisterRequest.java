package com.sahilprojects.bettingApp.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String displayName,                 // defaults to name when empty
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,  // 72 is the BCrypt limit
        @Size(max = 20) String phoneNumber) {
}
