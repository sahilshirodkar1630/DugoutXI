package com.sahilprojects.bettingApp.contest;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ContestParticipantRepository extends JpaRepository<ContestParticipant, Long> {

    Optional<ContestParticipant> findByContest_IdAndUser_Id(Long contestId, Long userId);

    /** True when someone other than the user has paid. A contest with paying participants cannot simply be cancelled. */
    boolean existsByContest_IdAndPaymentStatusAndUser_IdNot(Long contestId, ContestPaymentStatus paymentStatus, Long userId);

    /** Standalone contests the user takes part in or organizes (the organizer has a participant row), newest first. */
    @Query("select cp from ContestParticipant cp join fetch cp.contest c join fetch c.teamA join fetch c.teamB "
            + "where cp.user.id = :userId and c.league is null and cp.participantStatus not in :hidden "
            + "order by c.startTime desc")
    List<ContestParticipant> findStandaloneForUser(@Param("userId") Long userId,
                                                   @Param("hidden") java.util.Collection<ParticipantStatus> hidden);

    List<ContestParticipant> findByContest_IdAndParticipantStatus(Long contestId, ParticipantStatus status);

    long countByContest_IdAndParticipantStatus(Long contestId, ParticipantStatus status);

    List<ContestParticipant> findByUser_IdOrderByCreatedAtDesc(Long userId);

    /** Every participant row for a contest, with the user loaded, for the participants list. */
    @Query("select cp from ContestParticipant cp join fetch cp.user where cp.contest.id = :contestId "
            + "order by cp.requestedAt, cp.id")
    List<ContestParticipant> findByContestWithUser(@Param("contestId") Long contestId);

    /** Participants in one status with their user and pick loaded, for results, payouts and notifications. */
    @Query("select cp from ContestParticipant cp join fetch cp.user left join fetch cp.selectedTeam "
            + "where cp.contest.id = :contestId and cp.participantStatus = :status order by cp.id")
    List<ContestParticipant> findByStatusWithUser(@Param("contestId") Long contestId,
                                                  @Param("status") ParticipantStatus status);

    /** Standalone participants who actually paid, for refunds when a contest is cancelled. */
    @Query("select cp from ContestParticipant cp join fetch cp.user where cp.contest.id = :contestId "
            + "and cp.paymentStatus = :paymentStatus order by cp.id")
    List<ContestParticipant> findPaidWithUser(@Param("contestId") Long contestId,
                                              @Param("paymentStatus") ContestPaymentStatus paymentStatus);

    /** A league member's rows in contests that have not started yet (for removal or reversal). */
    @Query("select cp from ContestParticipant cp where cp.leagueMember.id = :leagueMemberId "
            + "and cp.contest.contestStatus = :status and cp.contest.startTime > :now")
    List<ContestParticipant> findInUnstartedContests(@Param("leagueMemberId") Long leagueMemberId,
                                                     @Param("status") ContestStatus status,
                                                     @Param("now") Instant now);

    /** Standalone payment cutoff: ACCEPTED_UNPAID participants whose contest payment deadline has passed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // SKIP LOCKED
    @Query("select cp from ContestParticipant cp join cp.contest c "
            + "where cp.participantStatus = :status and c.paymentDeadline <= :now")
    List<ContestParticipant> lockUnpaidPastDeadline(@Param("status") ParticipantStatus status, @Param("now") Instant now);
}
