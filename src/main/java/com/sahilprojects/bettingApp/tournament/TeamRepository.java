package com.sahilprojects.bettingApp.tournament;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByShortCodeIgnoreCase(String shortCode);

    boolean existsByShortCodeIgnoreCaseAndIdNot(String shortCode, Long id);

    /** The team picker for a tournament. */
    @Query("select t from Tournament tn join tn.teams t where tn.id = :tournamentId and t.active = true order by t.name")
    List<Team> findActiveByTournament(@Param("tournamentId") Long tournamentId);

    /** True when the team plays in the tournament (the database also enforces this on contests). */
    @Query("select count(t) > 0 from Tournament tn join tn.teams t where tn.id = :tournamentId and t.id = :teamId")
    boolean isInTournament(@Param("tournamentId") Long tournamentId, @Param("teamId") Long teamId);
}
