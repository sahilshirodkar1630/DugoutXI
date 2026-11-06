package com.sahilprojects.bettingApp.tournament;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.tournament.dto.TeamRequest;
import com.sahilprojects.bettingApp.tournament.dto.TeamResponse;
import com.sahilprojects.bettingApp.tournament.dto.TournamentRequest;
import com.sahilprojects.bettingApp.tournament.dto.TournamentResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tournaments, teams and which teams play in which tournament.
 * Everyone logged in can read the pickers. Only admins change them.
 * Creating and renaming are not audited. Deactivating and removing a team from a tournament are, with a reason.
 */
@Service
public class TournamentService {

    private final TournamentRepository tournaments;
    private final TeamRepository teams;
    private final AccessGuard accessGuard;
    private final AuditService auditService;

    public TournamentService(TournamentRepository tournaments, TeamRepository teams,
                             AccessGuard accessGuard, AuditService auditService) {
        this.tournaments = tournaments;
        this.teams = teams;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
    }

    // ---------- reading (the pickers) ----------

    @Transactional(readOnly = true)
    public List<TournamentResponse> listActiveTournaments() {
        return tournaments.findByActiveTrueOrderByNameAsc().stream().map(TournamentResponse::from).toList();
    }

    /** The team picker for a tournament: active teams only. */
    @Transactional(readOnly = true)
    public List<TeamResponse> listTeams(Long tournamentId) {
        loadTournament(tournamentId);
        return teams.findActiveByTournament(tournamentId).stream().map(TeamResponse::from).toList();
    }

    // ---------- admin listings (everything, including retired and inactive) ----------

    @Transactional(readOnly = true)
    public List<TournamentResponse> listAllTournaments(AuthenticatedUser actor) {
        accessGuard.requireAdmin(actor);
        return tournaments.findAll(Sort.by("name")).stream().map(TournamentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> listAllTeams(AuthenticatedUser actor) {
        accessGuard.requireAdmin(actor);
        return teams.findAll(Sort.by("shortCode")).stream().map(TeamResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> listAllTeamsInTournament(AuthenticatedUser actor, Long tournamentId) {
        accessGuard.requireAdmin(actor);
        return loadTournament(tournamentId).getTeams().stream()
                .sorted(java.util.Comparator.comparing(Team::getShortCode))
                .map(TeamResponse::from)
                .toList();
    }

    // ---------- tournaments (admin) ----------

    @Transactional
    public TournamentResponse createTournament(AuthenticatedUser actor, TournamentRequest request) {
        accessGuard.requireAdmin(actor);
        String name = request.name().trim();
        requireValidDates(request);
        if (tournaments.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A tournament with this name already exists");
        }
        Tournament t = new Tournament();
        t.setName(name);
        t.setStartDate(request.startDate());
        t.setEndDate(request.endDate());
        return TournamentResponse.from(tournaments.save(t));
    }

    @Transactional
    public TournamentResponse updateTournament(AuthenticatedUser actor, Long id, TournamentRequest request) {
        accessGuard.requireAdmin(actor);
        Tournament t = loadTournament(id);
        String name = request.name().trim();
        requireValidDates(request);
        if (tournaments.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("A tournament with this name already exists");
        }
        t.setName(name);
        t.setStartDate(request.startDate());
        t.setEndDate(request.endDate());
        return TournamentResponse.from(t);
    }

    /** Hides or shows a tournament in the pickers. Leagues and contests already using it carry on. */
    @Transactional
    public TournamentResponse setTournamentActive(AuthenticatedUser actor, Long id, boolean active, String reason) {
        accessGuard.requireAdminWithReason(actor, reason);
        Tournament t = loadTournament(id);
        boolean before = t.isActive();
        if (before != active) {
            t.setActive(active);
            auditActive(AuditAction.TOURNAMENT_ACTIVE_CHANGED, "TOURNAMENT", t.getId(), actor, reason, before, active);
        }
        return TournamentResponse.from(t);
    }

    // ---------- teams (admin) ----------

    @Transactional
    public TeamResponse createTeam(AuthenticatedUser actor, TeamRequest request) {
        accessGuard.requireAdmin(actor);
        String name = request.name().trim();
        String shortCode = normalizeCode(request.shortCode());
        if (teams.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A team with this name already exists");
        }
        if (teams.existsByShortCodeIgnoreCase(shortCode)) {
            throw new ConflictException("A team with this short code already exists");
        }
        Team team = new Team();
        team.setName(name);
        team.setShortCode(shortCode);
        team.setLogoUrl(blankToNull(request.logoUrl()));
        return TeamResponse.from(teams.save(team));
    }

    @Transactional
    public TeamResponse updateTeam(AuthenticatedUser actor, Long id, TeamRequest request) {
        accessGuard.requireAdmin(actor);
        Team team = loadTeam(id);
        String name = request.name().trim();
        String shortCode = normalizeCode(request.shortCode());
        if (teams.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("A team with this name already exists");
        }
        if (teams.existsByShortCodeIgnoreCaseAndIdNot(shortCode, id)) {
            throw new ConflictException("A team with this short code already exists");
        }
        team.setName(name);
        team.setShortCode(shortCode);
        team.setLogoUrl(blankToNull(request.logoUrl()));
        return TeamResponse.from(team);
    }

    /** A team is never deleted once used. Hiding it removes it from the pickers only. */
    @Transactional
    public TeamResponse setTeamActive(AuthenticatedUser actor, Long id, boolean active, String reason) {
        accessGuard.requireAdminWithReason(actor, reason);
        Team team = loadTeam(id);
        boolean before = team.isActive();
        if (before != active) {
            team.setActive(active);
            auditActive(AuditAction.TEAM_ACTIVE_CHANGED, "TEAM", team.getId(), actor, reason, before, active);
        }
        return TeamResponse.from(team);
    }

    // ---------- which teams play in which tournament (admin) ----------

    @Transactional
    public List<TeamResponse> addTeamToTournament(AuthenticatedUser actor, Long tournamentId, Long teamId) {
        accessGuard.requireAdmin(actor);
        Tournament tournament = loadTournament(tournamentId);
        Team team = loadTeam(teamId);
        if (!team.isActive()) {
            throw new BadRequestException("This team is inactive. Activate it before adding it to a tournament");
        }
        tournament.getTeams().add(team);          // adding twice does nothing, so this is safe to repeat
        return tournament.getTeams().stream()
                .sorted(java.util.Comparator.comparing(Team::getShortCode))
                .map(TeamResponse::from)
                .toList();
    }

    /** Refused while any contest in this tournament uses the team. The database enforces this too. */
    @Transactional
    public void removeTeamFromTournament(AuthenticatedUser actor, Long tournamentId, Long teamId, String reason) {
        accessGuard.requireAdminWithReason(actor, reason);
        Tournament tournament = loadTournament(tournamentId);
        boolean removed = tournament.getTeams().removeIf(t -> t.getId().equals(teamId));
        if (!removed) {
            throw new NotFoundException("This team does not play in this tournament");
        }
        try {
            tournaments.flush();                  // runs the delete now, so a contest that uses the team fails here
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("This team is used by contests in this tournament, so it cannot be removed");
        }
        auditService.record(AuditEntry.builder()
                .action(AuditAction.TOURNAMENT_TEAM_REMOVED)
                .entityType("TOURNAMENT").entityId(tournamentId)
                .actorId(actor.id()).actorType(ActorType.ADMIN).reason(reason)
                .before(Map.<String, Object>of("teamId", teamId))
                .after(Map.<String, Object>of("teamId", "removed"))
                .build());
    }

    // ---------- helpers ----------

    private Tournament loadTournament(Long id) {
        return tournaments.findById(id).orElseThrow(() -> new NotFoundException("Tournament not found"));
    }

    private Team loadTeam(Long id) {
        return teams.findById(id).orElseThrow(() -> new NotFoundException("Team not found"));
    }

    private void auditActive(AuditAction action, String entityType, Long entityId, AuthenticatedUser actor,
                             String reason, boolean before, boolean after) {
        auditService.record(AuditEntry.builder()
                .action(action).entityType(entityType).entityId(entityId)
                .actorId(actor.id()).actorType(ActorType.ADMIN).reason(reason)
                .before(Map.<String, Object>of("active", before))
                .after(Map.<String, Object>of("active", after))
                .build());
    }

    private static void requireValidDates(TournamentRequest request) {
        if (request.startDate() != null && request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BadRequestException("The end date cannot be before the start date");
        }
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
