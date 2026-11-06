package com.sahilprojects.bettingApp.league;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.league.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leagues")
public class LeagueController {

    private final LeagueService service;

    public LeagueController(LeagueService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeagueResponse create(@AuthenticationPrincipal AuthenticatedUser actor,
                                 @Valid @RequestBody CreateLeagueRequest request) {
        return service.create(actor, request);
    }

    @GetMapping
    public List<LeagueSummaryResponse> mine(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.listMine(actor);
    }

    /** The join screen for an invitation link. Declared before /{id} so the literal path wins. */
    @GetMapping("/invite/{code}")
    public LeagueResponse byInviteCode(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable String code) {
        return service.getByInviteCode(actor, code);
    }

    @GetMapping("/{id}")
    public LeagueResponse get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.get(actor, id);
    }

    @PutMapping("/{id}")
    public LeagueResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                 @Valid @RequestBody UpdateLeagueRequest request) {
        return service.update(actor, id, request);
    }

    @PostMapping("/{id}/open")
    public LeagueResponse open(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.openJoining(actor, id);
    }

    @PostMapping("/{id}/cancel")
    public LeagueResponse cancel(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                 @Valid @RequestBody(required = false) CancelLeagueRequest request) {
        return service.cancel(actor, id, request == null ? null : request.reason());
    }
}
