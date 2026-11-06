package com.sahilprojects.bettingApp.contest.dto;

/**
 * One participant's pick, as shown to everyone once the contest has started.
 * selectedTeamId is null for a confirmed participant who never predicted.
 */
public record PredictionRowResponse(
        long userId,
        String displayName,
        Long selectedTeamId,
        String selectedTeamShortCode,
        boolean me) {
}
