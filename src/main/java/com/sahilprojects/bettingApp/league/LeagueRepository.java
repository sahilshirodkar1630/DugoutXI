package com.sahilprojects.bettingApp.league;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LeagueRepository extends JpaRepository<League, Long> {

    Optional<League> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);

    /** Loads the organizer and tournament in the same query, for lists. */
    @Query("select l from League l join fetch l.organizer join fetch l.tournament where l.id in :ids order by l.createdAt desc")
    List<League> findAllWithOrganizerAndTournament(@Param("ids") Collection<Long> ids);

    List<League> findByOrganizer_IdOrderByCreatedAtDesc(Long organizerId);

    /** Leagues an organizer still has work to do on, such as one waiting to finish settling. */
    List<League> findByOrganizer_IdAndStatus(Long organizerId, LeagueStatus status);

    List<League> findByStatus(LeagueStatus status);

    /**
     * Locks the league row. Use when creating a contest or confirming a member, so the contest cap
     * and "enrol in every unstarted contest" stay consistent when two tabs act at once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from League l where l.id = :id")
    Optional<League> findByIdForUpdate(@Param("id") Long id);

    /** Open-joining job: leagues whose registration has started. Skips rows another run has locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // -2 = SKIP LOCKED
    @Query("select l from League l where l.status = :status and l.registrationStart <= :now")
    List<League> lockToOpen(@Param("status") LeagueStatus status, @Param("now") Instant now);
}
