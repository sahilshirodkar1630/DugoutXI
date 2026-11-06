package com.sahilprojects.bettingApp.contest;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ContestRepository extends JpaRepository<Contest, Long> {

    /** Locks one contest row so a start-time change, a cancel and the start job cannot interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Contest c where c.id = :id")
    java.util.Optional<Contest> findByIdForUpdate(@Param("id") Long id);

    /** For display: teams, tournament, organizer and league loaded in one query. */
    @Query("select c from Contest c join fetch c.teamA join fetch c.teamB join fetch c.tournament "
            + "join fetch c.organizer left join fetch c.league where c.id = :id")
    java.util.Optional<Contest> findByIdWithDetails(@Param("id") Long id);

    /** A league's contests with their teams, in start order. Cancelled ones are included. */
    @Query("select c from Contest c join fetch c.teamA join fetch c.teamB where c.league.id = :leagueId "
            + "order by c.startTime asc, c.id asc")
    List<Contest> findAllByLeagueWithTeams(@Param("leagueId") Long leagueId);

    /** Numbers already taken by contests that still count (cancelled ones free their number). */
    @Query("select c.contestNumber from Contest c where c.league.id = :leagueId and c.contestNumber is not null "
            + "and c.contestStatus <> :cancelled")
    List<Integer> findNumbersInUse(@Param("leagueId") Long leagueId, @Param("cancelled") ContestStatus cancelled);

    List<Contest> findByLeague_IdOrderByStartTimeAsc(Long leagueId);

    List<Contest> findByLeague_IdAndContestStatus(Long leagueId, ContestStatus status);

    /** Counts toward the planned total (cancelled contests do not). */
    long countByLeague_IdAndContestStatusNot(Long leagueId, ContestStatus status);

    /** Used when ending a league: any LOCKED contest still needs a result. */
    boolean existsByLeague_IdAndContestStatus(Long leagueId, ContestStatus status);

    /** League contests that have not started: new members are enrolled here, removed members are taken out. */
    @Query("select c from Contest c where c.league.id = :leagueId and c.contestStatus = :status and c.startTime > :now "
            + "order by c.startTime")
    List<Contest> findUnstartedByLeague(@Param("leagueId") Long leagueId,
                                        @Param("status") ContestStatus status,
                                        @Param("now") Instant now);

    /**
     * Start job: the ids of contests whose start time has passed and that are still SCHEDULED.
     *
     * Deliberately only the ids, and deliberately without a lock. The job handles each contest in its own
     * transaction and locks it there with findByIdForUpdate, re-checking the status once it holds the lock, so
     * locking the whole candidate list here would buy nothing and would hold locks across the entire batch.
     */
    @Query("select c.id from Contest c where c.contestStatus = :status and c.startTime <= :now order by c.startTime")
    List<Long> findIdsDueToStart(@Param("status") ContestStatus status, @Param("now") Instant now);

    /** Standalone contests (no league) for a user's list. */
    List<Contest> findByLeagueIsNullAndOrganizer_IdOrderByStartTimeDesc(Long organizerId);

    /** An organizer's to-do list: contests that have locked and are still waiting for a result. */
    @Query("select c from Contest c join fetch c.teamA join fetch c.teamB "
            + "where c.organizer.id = :organizerId and c.contestStatus = :status order by c.startTime")
    List<Contest> findLockedWithoutResultForOrganizer(@Param("organizerId") Long organizerId,
                                                      @Param("status") ContestStatus status);

    /** Contests whose start time is near, used by the start job and the reminder rebuild. */
    @Query("select c from Contest c join fetch c.teamA join fetch c.teamB "
            + "where c.contestStatus = :status and c.startTime between :from and :to order by c.startTime")
    List<Contest> findStartingBetween(@Param("status") ContestStatus status,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to);
}
