package com.sahilprojects.bettingApp.tournament;

import com.sahilprojects.bettingApp.tournament.dto.TeamResponse;
import com.sahilprojects.bettingApp.tournament.dto.TournamentResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The pickers. Any logged-in user can read them. */
@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService service;

    public TournamentController(TournamentService service) {
        this.service = service;
    }

    @GetMapping
    public List<TournamentResponse> list() {
        return service.listActiveTournaments();
    }

    @GetMapping("/{id}/teams")
    public List<TeamResponse> teams(@PathVariable Long id) {
        return service.listTeams(id);
    }
}
