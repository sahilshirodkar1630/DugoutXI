package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.contest.dto.*;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.league.LeagueRepository;
import com.sahilprojects.bettingApp.league.LeagueStatus;
import com.sahilprojects.bettingApp.tournament.Team;
import com.sahilprojects.bettingApp.tournament.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Creating a league's matches from a fixture list (the frontend reads it from a CSV or Excel export), instead of one
 * at a time.
 *
 * All or nothing: every row is checked first, and nothing is created unless every row passes, so a typo on row 40
 * cannot leave a half-imported season. Each match is then made through ContestService.create, exactly as if it had
 * been added by hand, so it gets the same checks, enrolment, reminders and audit entry.
 */
@Service
public class FixtureImportService {

    private static final List<LeagueStatus> ACCEPTS_NEW_CONTESTS =
            List.of(LeagueStatus.UPCOMING, LeagueStatus.OPEN, LeagueStatus.ACTIVE);

    private final LeagueRepository leagues;
    private final ContestRepository contests;
    private final TeamRepository teams;
    private final ContestService contestService;
    private final AccessGuard accessGuard;
    private final Clock clock;

    public FixtureImportService(LeagueRepository leagues, ContestRepository contests, TeamRepository teams,
                                ContestService contestService, AccessGuard accessGuard, Clock clock) {
        this.leagues = leagues;
        this.contests = contests;
        this.teams = teams;
        this.contestService = contestService;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    @Transactional
    public ImportFixturesResponse importFixtures(AuthenticatedUser actor, Long leagueId, ImportFixturesRequest request) {
        League league = leagues.findByIdForUpdate(leagueId).orElseThrow(() -> new NotFoundException("League not found"));
        accessGuard.requireOrganizer(actor, league);
        if (!ACCEPTS_NEW_CONTESTS.contains(league.getStatus())) {
            throw new ConflictException("No new contests can be added because the league is " + league.getStatus());
        }

        List<Planned> plan = check(league, request.rows());
        boolean ok = plan.stream().allMatch(p -> p.error == null);
        if (!ok || request.dryRun()) {
            return new ImportFixturesResponse(ok, request.dryRun(), 0, plan.stream().map(p -> p.result(null)).toList());
        }

        List<FixtureRowResult> results = new ArrayList<>();
        for (Planned p : plan) {
            ContestResponse created = contestService.create(actor, new CreateContestRequest(
                    league.getId(), p.teamA.getId(), p.teamB.getId(), p.row.startTime(), p.row.name(), p.number,
                    null, null, null, null, null));
            results.add(p.result(created.id()));
        }
        return new ImportFixturesResponse(true, false, results.size(), results);
    }

    private List<Planned> check(League league, List<FixtureRow> rows) {
        Instant now = clock.instant();
        Long tournamentId = league.getTournament().getId();
        Map<String, Team> byName = new HashMap<>();
        for (Team team : teams.findActiveByTournament(tournamentId)) {
            byName.put(key(team.getShortCode()), team);
            byName.put(key(team.getName()), team);
        }

        List<Contest> existing = contests.findAllByLeagueWithTeams(league.getId()).stream()
                .filter(c -> c.getContestStatus() != ContestStatus.CANCELLED)
                .toList();
        Set<String> taken = new HashSet<>();
        existing.forEach(c -> taken.add(matchKey(c.getTeamA(), c.getTeamB(), c.getStartTime())));
        Set<Integer> usedNumbers = new HashSet<>();
        existing.forEach(c -> usedNumbers.add(c.getContestNumber()));

        int room = league.getTotalContests() - existing.size();
        String tournamentName = league.getTournament().getName();

        List<Planned> plan = new ArrayList<>();
        Set<Integer> askedFor = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            FixtureRow row = rows.get(i);
            Planned p = new Planned(i + 1, row);
            p.teamA = byName.get(key(row.teamA()));
            p.teamB = byName.get(key(row.teamB()));
            Integer n = row.contestNumber();

            if (i >= room) {
                p.error = room <= 0
                        ? "All " + league.getTotalContests() + " planned contests already exist"
                        : "Only " + room + " more contests fit in this league (it plans " + league.getTotalContests() + ")";
            } else if (p.teamA == null) {
                p.error = "\"" + row.teamA() + "\" is not a team playing in " + tournamentName;
            } else if (p.teamB == null) {
                p.error = "\"" + row.teamB() + "\" is not a team playing in " + tournamentName;
            } else if (p.teamA.getId().equals(p.teamB.getId())) {
                p.error = "A match needs two different teams";
            } else if (!row.startTime().isAfter(now)) {
                p.error = "The start time has already passed";
            } else if (!row.startTime().isAfter(league.getPaymentDeadline())) {
                p.error = "The start time must be after the league's payment deadline";
            } else if (!taken.add(matchKey(p.teamA, p.teamB, row.startTime()))) {
                p.error = "This match is already in the league (or twice in the list)";
            } else if (n != null && n > league.getTotalContests()) {
                p.error = "Match number " + n + " is higher than the league's " + league.getTotalContests() + " contests";
            } else if (n != null && (usedNumbers.contains(n) || !askedFor.add(n))) {
                p.error = "Match number " + n + " is already used";
            }
            plan.add(p);
        }

        // Rows without a number get the lowest free ones, in the order they were listed.
        Set<Integer> reserved = new HashSet<>(usedNumbers);
        plan.stream().filter(p -> p.error == null && p.row.contestNumber() != null)
                .forEach(p -> {
                    p.number = p.row.contestNumber();
                    reserved.add(p.number);
                });
        int next = 1;
        for (Planned p : plan) {
            if (p.error != null || p.number != null) continue;
            while (reserved.contains(next)) next++;
            p.number = next;
            reserved.add(next);
        }
        return plan;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** The same two teams at the same time is the same match, whichever way round they are listed. */
    private static String matchKey(Team a, Team b, Instant start) {
        long low = Math.min(a.getId(), b.getId());
        long high = Math.max(a.getId(), b.getId());
        return low + "-" + high + "@" + start.getEpochSecond();
    }

    private static final class Planned {
        final int index;
        final FixtureRow row;
        Team teamA;
        Team teamB;
        Integer number;
        String error;

        Planned(int index, FixtureRow row) {
            this.index = index;
            this.row = row;
        }

        FixtureRowResult result(Long contestId) {
            return new FixtureRowResult(index,
                    teamA == null ? null : TeamRef.from(teamA),
                    teamB == null ? null : TeamRef.from(teamB),
                    row.startTime(), row.name(), error == null ? number : row.contestNumber(), contestId, error);
        }
    }
}
