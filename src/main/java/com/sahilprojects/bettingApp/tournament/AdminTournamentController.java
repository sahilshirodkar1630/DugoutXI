package com.sahilprojects.bettingApp.tournament;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.tournament.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin only (SecurityConfig protects /api/admin/**, and the service checks again). */
@RestController
@RequestMapping("/api/admin")
public class AdminTournamentController {

    private final TournamentService service;

    public AdminTournamentController(TournamentService service) {
        this.service = service;
    }

    /** Every tournament, retired ones included, so a retired one can be brought back. */
    @GetMapping("/tournaments")
    public List<TournamentResponse> allTournaments(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.listAllTournaments(actor);
    }

    /** Every team, inactive ones included, for linking teams to tournaments. */
    @GetMapping("/teams")
    public List<TeamResponse> allTeams(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.listAllTeams(actor);
    }

    /** The teams linked to a tournament, inactive ones included (the public picker shows active teams only). */
    @GetMapping("/tournaments/{id}/teams")
    public List<TeamResponse> tournamentTeams(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.listAllTeamsInTournament(actor, id);
    }

    @PostMapping("/tournaments")
    @ResponseStatus(HttpStatus.CREATED)
    public TournamentResponse createTournament(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @Valid @RequestBody TournamentRequest request) {
        return service.createTournament(actor, request);
    }

    @PutMapping("/tournaments/{id}")
    public TournamentResponse updateTournament(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @PathVariable Long id, @Valid @RequestBody TournamentRequest request) {
        return service.updateTournament(actor, id, request);
    }

    @PutMapping("/tournaments/{id}/active")
    public TournamentResponse setTournamentActive(@AuthenticationPrincipal AuthenticatedUser actor,
                                                  @PathVariable Long id, @Valid @RequestBody SetActiveRequest request) {
        return service.setTournamentActive(actor, id, request.active(), request.reason());
    }

    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse createTeam(@AuthenticationPrincipal AuthenticatedUser actor,
                                   @Valid @RequestBody TeamRequest request) {
        return service.createTeam(actor, request);
    }

    @PutMapping("/teams/{id}")
    public TeamResponse updateTeam(@AuthenticationPrincipal AuthenticatedUser actor,
                                   @PathVariable Long id, @Valid @RequestBody TeamRequest request) {
        return service.updateTeam(actor, id, request);
    }

    @PutMapping("/teams/{id}/active")
    public TeamResponse setTeamActive(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long id, @Valid @RequestBody SetActiveRequest request) {
        return service.setTeamActive(actor, id, request.active(), request.reason());
    }

    @PutMapping("/tournaments/{tournamentId}/teams/{teamId}")
    public List<TeamResponse> addTeam(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long tournamentId, @PathVariable Long teamId) {
        return service.addTeamToTournament(actor, tournamentId, teamId);
    }

    @DeleteMapping("/tournaments/{tournamentId}/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTeam(@AuthenticationPrincipal AuthenticatedUser actor,
                           @PathVariable Long tournamentId, @PathVariable Long teamId,
                           @RequestParam String reason) {
        service.removeTeamFromTournament(actor, tournamentId, teamId, reason);
    }
}
