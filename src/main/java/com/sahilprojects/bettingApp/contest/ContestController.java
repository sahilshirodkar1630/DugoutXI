package com.sahilprojects.bettingApp.contest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.contest.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Contests")
@RestController
@RequestMapping("/api")
public class ContestController {

    private final ContestService service;

    public ContestController(ContestService service) {
        this.service = service;
    }

    /** League contest (send leagueId) or standalone contest (leave it out). */
    @Operation(summary = "Create a contest",
            description = "For a league match send `leagueId` (tournament, amount and deadlines come from the league). For a one-off contest leave it out and send `tournamentId`, `amount`, `registrationDeadline` and `paymentDeadline`.")
    @PostMapping("/contests")
    @ResponseStatus(HttpStatus.CREATED)
    public ContestResponse create(@AuthenticationPrincipal AuthenticatedUser actor,
                                  @Valid @RequestBody CreateContestRequest request) {
        return service.create(actor, request);
    }

    /** Declared before /contests/{id} so the literal path wins. */
    @Operation(summary = "Your one-off contests",
            description = "Ones you organize or have a place in, newest first.")
    @GetMapping("/contests/mine")
    public List<ContestSummaryResponse> mine(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.listMyStandalone(actor);
    }

    @Operation(summary = "A contest",
            description = "Any signed-in user can open a one-off contest, so its link can be shared. A league contest is for members only.")
    @GetMapping("/contests/{id}")
    public ContestResponse get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.get(actor, id);
    }

    @Operation(summary = "The matches in a league",
            description = "Members only.")
    @GetMapping("/leagues/{leagueId}/contests")
    public List<ContestSummaryResponse> leagueContests(@AuthenticationPrincipal AuthenticatedUser actor,
                                                       @PathVariable Long leagueId) {
        return service.listForLeague(actor, leagueId);
    }

    @Operation(summary = "Move a contest's start time",
            description = "Organizer of this league or contest, or an admin with a reason. Everyone in it is told, and reminders are rescheduled.")
    @PatchMapping("/contests/{id}/start-time")
    public ContestResponse changeStartTime(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                           @Valid @RequestBody ChangeStartTimeRequest request) {
        return service.changeStartTime(actor, id, request);
    }

    @Operation(summary = "Cancel a contest",
            description = "Organizer of this league or contest, or an admin with a reason. In a one-off contest everyone who paid is refunded.")
    @PostMapping("/contests/{id}/cancel")
    public ContestResponse cancel(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                  @Valid @RequestBody(required = false) CancelContestRequest request) {
        return service.cancel(actor, id, request == null ? null : request.reason());
    }
}
