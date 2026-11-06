package com.sahilprojects.bettingApp.audit;

import lombok.Builder;

import java.util.Map;

/** What a service passes to AuditService. Whether members can see it follows from the action. */
@Builder
public record AuditEntry(
        AuditAction action,
        String entityType,
        Long entityId,
        Long leagueId,
        Long contestId,
        Long actorId,              // null for scheduled jobs
        ActorType actorType,
        String reason,             // mandatory when actorType is ADMIN
        Map<String, Object> before,
        Map<String, Object> after) {
}
