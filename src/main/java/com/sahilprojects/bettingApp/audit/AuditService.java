package com.sahilprojects.bettingApp.audit;

import com.sahilprojects.bettingApp.contest.Contest;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.user.User;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit row inside the caller's transaction. MANDATORY means calling it without a transaction fails,
 * so a change and its audit entry always succeed or fail together.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final EntityManager entityManager;

    public AuditService(AuditLogRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        if (entry.action() == null || entry.entityType() == null || entry.entityId() == null || entry.actorType() == null) {
            throw new IllegalArgumentException("Audit entry needs an action, entity type, entity id and actor type");
        }
        if (entry.actorType() != ActorType.SYSTEM && entry.actorId() == null) {
            throw new IllegalArgumentException("Audit entry needs an actor id unless the actor is SYSTEM");
        }
        if (entry.actorType() == ActorType.ADMIN && (entry.reason() == null || entry.reason().isBlank())) {
            throw new IllegalArgumentException("An admin audit entry needs a reason");
        }

        AuditLog log = new AuditLog();
        log.setAction(entry.action().name());
        log.setEntityType(entry.entityType());
        log.setEntityId(entry.entityId());
        if (entry.leagueId() != null) {
            log.setLeague(entityManager.getReference(League.class, entry.leagueId()));
        }
        if (entry.contestId() != null) {
            log.setContest(entityManager.getReference(Contest.class, entry.contestId()));
        }
        if (entry.actorId() != null) {
            log.setActor(entityManager.getReference(User.class, entry.actorId()));
        }
        log.setActorType(entry.actorType());
        log.setReason(entry.reason() == null ? null : entry.reason().trim());
        log.setBeforeData(entry.before());
        log.setAfterData(entry.after());
        log.setVisibleToMembers(entry.action().isVisibleToMembers());
        repository.save(log);
    }
}
