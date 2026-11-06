package com.sahilprojects.bettingApp.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

/** The league table, plus the caller's own line so the frontend can highlight it without searching. */
public record LeagueBoardResponse(
        long leagueId,
        String leagueName,
        String leagueStatus,
        int contestsPlayed,
        int contestsPlanned,
        BigDecimal amountPerContest,
        BigDecimal joiningAmount,
        BigDecimal totalLeftover,
        List<LeagueBoardRowResponse> rows,
        LeagueBoardRowResponse me) {
}
