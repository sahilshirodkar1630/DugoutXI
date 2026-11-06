package com.sahilprojects.bettingApp.league;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.league.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Leagues")
@RestController
@RequestMapping("/api/leagues")
public class LeagueController {

    private final LeagueService service;

    public LeagueController(LeagueService service) {
        this.service = service;
    }

    @Operation(summary = "Create a league",
            description = "Starts UPCOMING. You are its organizer and a confirmed, paid member. The buy-in is `amountPerContest × totalContests`.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeagueResponse create(@AuthenticationPrincipal AuthenticatedUser actor,
                                 @Valid @RequestBody CreateLeagueRequest request) {
        return service.create(actor, request);
    }

    @Operation(summary = "Your leagues",
            description = "Leagues you organize or belong to.")
    @GetMapping
    public List<LeagueSummaryResponse> mine(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.listMine(actor);
    }

    /** The join screen for an invitation link. Declared before /{id} so the literal path wins. */
    @Operation(summary = "Look a league up by its invite code",
            description = "What an invite link opens: enough to decide whether to ask to join.")
    @GetMapping("/invite/{code}")
    public LeagueResponse byInviteCode(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable String code) {
        return service.getByInviteCode(actor, code);
    }

    @Operation(summary = "A league",
            description = "The invite code is only filled in for the organizer and admins.")
    @GetMapping("/{id}")
    public LeagueResponse get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.get(actor, id);
    }

    @Operation(summary = "Edit a league",
            description = "Organizer of this league or contest, or an admin with a reason. Only before it starts. Send the `version` you read; a stale one is refused (409).")
    @PutMapping("/{id}")
    public LeagueResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                 @Valid @RequestBody UpdateLeagueRequest request) {
        return service.update(actor, id, request);
    }

    @Operation(summary = "Open a league for joining",
            description = "Organizer of this league or contest, or an admin with a reason.")
    @PostMapping("/{id}/open")
    public LeagueResponse open(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.openJoining(actor, id);
    }

    @Operation(summary = "Cancel a league",
            description = "Organizer of this league or contest, or an admin with a reason. Only before it starts. Anyone who paid gets a settlement row, so there is a list of money to hand back.")
    @PostMapping("/{id}/cancel")
    public LeagueResponse cancel(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                 @Valid @RequestBody(required = false) CancelLeagueRequest request) {
        return service.cancel(actor, id, request == null ? null : request.reason());
    }
}
