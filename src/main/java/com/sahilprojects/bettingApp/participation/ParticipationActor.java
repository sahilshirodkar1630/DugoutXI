package com.sahilprojects.bettingApp.participation;

import com.sahilprojects.bettingApp.audit.ActorType;

/**
 * Who is making the change and in what capacity. Callers decide this with AccessGuard first.
 * adminReason is the written reason when an admin overrides an organizer. The audit log refuses an admin entry without one.
 */
public record ParticipationActor(Long userId, ActorType type, String adminReason) {

    public static ParticipationActor user(Long userId) {
        return new ParticipationActor(userId, ActorType.USER, null);
    }

    public static ParticipationActor admin(Long userId, String reason) {
        return new ParticipationActor(userId, ActorType.ADMIN, reason);
    }

    /** For scheduled jobs. */
    public static ParticipationActor system() {
        return new ParticipationActor(null, ActorType.SYSTEM, null);
    }
}
