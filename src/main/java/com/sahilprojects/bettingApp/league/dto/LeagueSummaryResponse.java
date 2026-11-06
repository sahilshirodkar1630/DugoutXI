package com.sahilprojects.bettingApp.league.dto;

import com.sahilprojects.bettingApp.league.LeagueStatus;

import java.math.BigDecimal;

public record LeagueSummaryResponse(
        Long id,
        String name,
        String tournamentName,
        String organizerDisplayName,
        BigDecimal amountPerContest,
        Integer totalContests,
        BigDecimal joiningAmount,
        LeagueStatus status,
        String myMembershipStatus,
        boolean organizer,
        long confirmedMembers) {
}
