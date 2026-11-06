package com.sahilprojects.bettingApp.tournament;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.tournament.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin only (SecurityConfig protects /api/admin/**, and the service checks again). */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/admin")
public class AdminTournamentController {

    private final TournamentService service;

    public AdminTournamentController(TournamentService service) {
        this.service = service;
    }

    /** Every tournament, retired ones included, so a retired one can be brought back. */
    @Operation(summary = "All tournaments, including retired ones")
    @GetMapping("/tournaments")
    public List<TournamentResponse> allTournaments(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.listAllTournaments(actor);
    }

    /** Every team, inactive ones included, for linking teams to tournaments. */
    @Operation(summary = "All teams, including inactive ones")
    @GetMapping("/teams")
    public List<TeamResponse> allTeams(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.listAllTeams(actor);
    }

    /** The teams linked to a tournament, inactive ones included (the public picker shows active teams only). */
    @Operation(summary = "Every team linked to a tournament",
            description = "Inactive ones included, so an admin can see the whole list.")
    @GetMapping("/tournaments/{id}/teams")
    public List<TeamResponse> tournamentTeams(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.listAllTeamsInTournament(actor, id);
    }

    @Operation(summary = "Add a tournament")
    @PostMapping("/tournaments")
    @ResponseStatus(HttpStatus.CREATED)
    public TournamentResponse createTournament(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @Valid @RequestBody TournamentRequest request) {
        return service.createTournament(actor, request);
    }

    @Operation(summary = "Edit a tournament")
    @PutMapping("/tournaments/{id}")
    public TournamentResponse updateTournament(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @PathVariable Long id, @Valid @RequestBody TournamentRequest request) {
        return service.updateTournament(actor, id, request);
    }

    @Operation(summary = "Retire or bring back a tournament",
            description = "Needs a reason. A retired tournament takes no new leagues or contests.")
    @PutMapping("/tournaments/{id}/active")
    public TournamentResponse setTournamentActive(@AuthenticationPrincipal AuthenticatedUser actor,
                                                  @PathVariable Long id, @Valid @RequestBody SetActiveRequest request) {
        return service.setTournamentActive(actor, id, request.active(), request.reason());
    }

    @Operation(summary = "Add a team",
            description = "A team is stored once and linked to each tournament it plays in. Short code: 2 to 6 letters or digits.")
    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse createTeam(@AuthenticationPrincipal AuthenticatedUser actor,
                                   @Valid @RequestBody TeamRequest request) {
        return service.createTeam(actor, request);
    }

    @Operation(summary = "Edit a team")
    @PutMapping("/teams/{id}")
    public TeamResponse updateTeam(@AuthenticationPrincipal AuthenticatedUser actor,
                                   @PathVariable Long id, @Valid @RequestBody TeamRequest request) {
        return service.updateTeam(actor, id, request);
    }

    @Operation(summary = "Deactivate or reactivate a team",
            description = "Needs a reason.")
    @PutMapping("/teams/{id}/active")
    public TeamResponse setTeamActive(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long id, @Valid @RequestBody SetActiveRequest request) {
        return service.setTeamActive(actor, id, request.active(), request.reason());
    }

    @Operation(summary = "Link a team to a tournament")
    @PutMapping("/tournaments/{tournamentId}/teams/{teamId}")
    public List<TeamResponse> addTeam(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long tournamentId, @PathVariable Long teamId) {
        return service.addTeamToTournament(actor, tournamentId, teamId);
    }

    @Operation(summary = "Unlink a team from a tournament",
            description = "Needs a `reason` query parameter. Refused while a match uses the team.")
    @DeleteMapping("/tournaments/{tournamentId}/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTeam(@AuthenticationPrincipal AuthenticatedUser actor,
                           @PathVariable Long tournamentId, @PathVariable Long teamId,
                           @RequestParam String reason) {
        service.removeTeamFromTournament(actor, tournamentId, teamId, reason);
    }
}
