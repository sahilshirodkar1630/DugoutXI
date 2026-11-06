package com.sahilprojects.bettingApp.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** History that league members are allowed to see (start time, result and correction changes). */
    List<AuditLog> findByLeague_IdAndVisibleToMembersTrueOrderByCreatedAtDesc(Long leagueId);

    List<AuditLog> findByContest_IdAndVisibleToMembersTrueOrderByCreatedAtDesc(Long contestId);

    /** Admin view of everything about one entity. */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);
}
