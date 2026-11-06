package com.sahilprojects.bettingApp.tournament.dto;

import com.sahilprojects.bettingApp.tournament.Tournament;

import java.time.LocalDate;

public record TournamentResponse(Long id, String name, LocalDate startDate, LocalDate endDate, boolean active) {

    public static TournamentResponse from(Tournament t) {
        return new TournamentResponse(t.getId(), t.getName(), t.getStartDate(), t.getEndDate(), t.isActive());
    }
}
