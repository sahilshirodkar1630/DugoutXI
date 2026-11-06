package com.sahilprojects.bettingApp.payout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    List<Payout> findByContest_IdAndPayoutStatusNot(Long contestId, PayoutStatus status);

    /** A result correction is refused while this is true. */
    boolean existsByContest_IdAndPayoutStatus(Long contestId, PayoutStatus status);

    /** The contest becomes SETTLED when no payout is PENDING. */
    boolean existsByContest_IdAndPayoutStatusIn(Long contestId, java.util.Collection<PayoutStatus> statuses);

    List<Payout> findByUser_IdAndPayoutStatus(Long userId, PayoutStatus status);

    /** Every payout for a contest, oldest first, with the user loaded for display. */
    @Query("select p from Payout p join fetch p.user where p.contest.id = :contestId order by p.id")
    List<Payout> findByContestWithUser(@Param("contestId") Long contestId);

    List<Payout> findByContest_Id(Long contestId);

    /** A participant's own payouts for a contest. */
    List<Payout> findByContest_IdAndUser_Id(Long contestId, Long userId);

    /** Money still owed to a user across standalone contests, for their dashboard. */
    @Query("select p from Payout p join fetch p.contest c join fetch c.teamA join fetch c.teamB "
            + "where p.user.id = :userId and p.payoutStatus = :status order by p.id desc")
    List<Payout> findForUserWithContest(@Param("userId") Long userId, @Param("status") PayoutStatus status);

    /** True when a correction would disturb money that has already changed hands, other than the organizer's own. */
    @Query("select count(p) > 0 from Payout p where p.contest.id = :contestId "
            + "and p.payoutStatus = com.sahilprojects.bettingApp.payout.PayoutStatus.PAID_OUT "
            + "and p.user.id <> :organizerId")
    boolean existsPaidOutToSomeoneElse(@Param("contestId") Long contestId, @Param("organizerId") Long organizerId);
}
