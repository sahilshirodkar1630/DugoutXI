package com.sahilprojects.bettingApp.contest.dto;

import com.sahilprojects.bettingApp.tournament.Team;

public record TeamRef(Long id, String name, String shortCode) {

    public static TeamRef from(Team t) {
        return new TeamRef(t.getId(), t.getName(), t.getShortCode());
    }
}
