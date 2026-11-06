package com.sahilprojects.bettingApp.tournament;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.tournament.dto.TeamResponse;
import com.sahilprojects.bettingApp.tournament.dto.TournamentResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The pickers. Any logged-in user can read them. */
@Tag(name = "Tournaments")
@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService service;

    public TournamentController(TournamentService service) {
        this.service = service;
    }

    @Operation(summary = "Active tournaments",
            description = "The ones a new league or contest can be created on.")
    @GetMapping
    public List<TournamentResponse> list() {
        return service.listActiveTournaments();
    }

    @Operation(summary = "Teams playing in a tournament",
            description = "Only active teams linked to the tournament; these are the teams a match can use.")
    @GetMapping("/{id}/teams")
    public List<TeamResponse> teams(@PathVariable Long id) {
        return service.listTeams(id);
    }
}
