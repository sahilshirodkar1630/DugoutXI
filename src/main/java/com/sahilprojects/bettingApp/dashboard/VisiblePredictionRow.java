package com.sahilprojects.bettingApp.dashboard;

/** Another participant's pick. The underlying view returns rows only after the contest has started. */
public record VisiblePredictionRow(long userId, String displayName, long selectedTeamId) {
}
