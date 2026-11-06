package com.sahilprojects.bettingApp.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TeamRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{2,6}$", message = "must be 2 to 6 letters or digits") String shortCode,
        @Pattern(regexp = "^https?://\\S+$", message = "must be an http or https URL") @Size(max = 500) String logoUrl) {
}
