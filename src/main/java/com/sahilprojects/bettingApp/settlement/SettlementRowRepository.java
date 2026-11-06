package com.sahilprojects.bettingApp.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SettlementRowRepository extends JpaRepository<SettlementRow, Long> {

    List<SettlementRow> findByLeague_IdOrderByFinalBalanceDesc(Long leagueId);

    List<SettlementRow> findByLeague_IdAndSettlementStatus(Long leagueId, SettlementStatus status);

    /** Completing the league is refused while this is true. */
    boolean existsByLeague_IdAndSettlementStatus(Long leagueId, SettlementStatus status);

    /** A user's open settlements, with the league loaded for their dashboard. */
    @Query("select r from SettlementRow r join fetch r.league where r.user.id = :userId "
            + "and r.settlementStatus = :status order by r.id desc")
    List<SettlementRow> findByUser_IdAndSettlementStatus(@Param("userId") Long userId,
                                                         @Param("status") SettlementStatus status);

    /**
     * True when a member other than the organizer has already settled up. A result correction is refused while this
     * holds: that member has handed money over on the strength of a balance that would now change.
     *
     * The organizer's own row is excluded on purpose. They hold the pot, so their balance is the mirror of
     * everybody else's and has to be recalculated rather than frozen. So is a row with a final balance of zero: it
     * was marked settled because there was nothing to return, so no money has moved on the strength of it.
     */
    @Query("select count(r) > 0 from SettlementRow r where r.league.id = :leagueId "
            + "and r.settlementStatus = :status and r.organizer = false "
            + "and r.finalBalance <> 0")
    boolean existsSettledForSomeoneOtherThanTheOrganizer(@Param("leagueId") Long leagueId,
                                                         @Param("status") SettlementStatus status);
}
