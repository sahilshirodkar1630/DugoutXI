package com.sahilprojects.bettingApp.league.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RemoveMemberRequest(@NotBlank @Size(max = 500) String reason) {
}
