package com.sahilprojects.bettingApp.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record TournamentRequest(
        @NotBlank @Size(max = 100) String name,
        LocalDate startDate,
        LocalDate endDate) {
}
