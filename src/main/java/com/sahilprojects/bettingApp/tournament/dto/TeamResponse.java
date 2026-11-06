package com.sahilprojects.bettingApp.tournament.dto;

import com.sahilprojects.bettingApp.tournament.Team;

public record TeamResponse(Long id, String name, String shortCode, String logoUrl, boolean active) {

    public static TeamResponse from(Team t) {
        return new TeamResponse(t.getId(), t.getName(), t.getShortCode(), t.getLogoUrl(), t.isActive());
    }
}
