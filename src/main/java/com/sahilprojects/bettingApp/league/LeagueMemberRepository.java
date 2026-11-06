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

public interface LeagueMemberRepository extends JpaRepository<LeagueMember, Long> {

    Optional<LeagueMember> findByLeague_IdAndUser_Id(Long leagueId, Long userId);

    /** Every member of a league with the user loaded, oldest request first. */
    @Query("select m from LeagueMember m join fetch m.user where m.league.id = :leagueId order by m.requestedAt asc, m.id asc")
    List<LeagueMember> findAllWithUser(@Param("leagueId") Long leagueId);

    List<LeagueMember> findByLeague_IdAndLeagueMemberStatus(Long leagueId, LeagueMemberStatus status);

    List<LeagueMember> findByLeague_IdAndPaymentStatus(Long leagueId, PaymentStatus paymentStatus);

    List<LeagueMember> findByUser_Id(Long userId);

    long countByLeague_IdAndLeagueMemberStatus(Long leagueId, LeagueMemberStatus status);

    /** A user's memberships in the given statuses, newest first. Used for "my leagues". */
    List<LeagueMember> findByUser_IdAndLeagueMemberStatusIn(Long userId, Collection<LeagueMemberStatus> statuses);

    /** Members other than the organizer in these statuses. The organizer's own row is CONFIRMED from the start, so it must not count. */
    boolean existsByLeague_IdAndLeagueMemberStatusInAndUser_IdNot(Long leagueId, Collection<LeagueMemberStatus> statuses, Long userId);

    /** True when someone other than the organizer has paid. A league with paying members cannot simply be cancelled. */
    boolean existsByLeague_IdAndPaymentStatusAndUser_IdNot(Long leagueId, PaymentStatus paymentStatus, Long userId);

    /** Used for the rule "league amount and total are locked once a member is ACCEPTED_UNPAID or CONFIRMED". */
    boolean existsByLeague_IdAndLeagueMemberStatusIn(Long leagueId, Collection<LeagueMemberStatus> statuses);

    /** Payment cutoff job: ACCEPTED_UNPAID members whose league payment deadline has passed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // SKIP LOCKED
    @Query("select lm from LeagueMember lm join lm.league l "
            + "where lm.leagueMemberStatus = :status and l.paymentDeadline <= :now")
    List<LeagueMember> lockUnpaidPastDeadline(@Param("status") LeagueMemberStatus status, @Param("now") Instant now);
}
