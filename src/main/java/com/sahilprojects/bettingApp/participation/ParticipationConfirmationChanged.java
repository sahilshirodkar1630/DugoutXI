package com.sahilprojects.bettingApp.participation;

/**
 * Published when someone gains or loses CONFIRMED. Listeners run in the same transaction (a plain @EventListener,
 * not a transactional one), so a failure in a listener rolls back the status change too. EnrolmentService will use this
 * to enrol a new league member in every unstarted contest, or take a removed one out.
 */
public record ParticipationConfirmationChanged(
        ParticipationKind kind,
        Long participationId,
        Long leagueId,
        Long contestId,
        Long userId,
        boolean confirmed) {
}
