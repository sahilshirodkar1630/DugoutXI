package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.contest.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ContestController {

    private final ContestService service;
    private final FixtureImportService fixtureImport;

    public ContestController(ContestService service, FixtureImportService fixtureImport) {
        this.service = service;
        this.fixtureImport = fixtureImport;
    }

    /** League contest (send leagueId) or standalone contest (leave it out). */
    @PostMapping("/contests")
    @ResponseStatus(HttpStatus.CREATED)
    public ContestResponse create(@AuthenticationPrincipal AuthenticatedUser actor,
                                  @Valid @RequestBody CreateContestRequest request) {
        return service.create(actor, request);
    }

    /** Declared before /contests/{id} so the literal path wins. */
    @GetMapping("/contests/mine")
    public List<ContestSummaryResponse> mine(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.listMyStandalone(actor);
    }

    @GetMapping("/contests/{id}")
    public ContestResponse get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.get(actor, id);
    }

    @GetMapping("/leagues/{leagueId}/contests")
    public List<ContestSummaryResponse> leagueContests(@AuthenticationPrincipal AuthenticatedUser actor,
                                                       @PathVariable Long leagueId) {
        return service.listForLeague(actor, leagueId);
    }

    /** Many league matches at once, all or nothing. Send dryRun to check the list without creating anything. */
    @PostMapping("/leagues/{leagueId}/contests/import")
    public ImportFixturesResponse importFixtures(@AuthenticationPrincipal AuthenticatedUser actor,
                                                 @PathVariable Long leagueId,
                                                 @Valid @RequestBody ImportFixturesRequest request) {
        return fixtureImport.importFixtures(actor, leagueId, request);
    }

    @PatchMapping("/contests/{id}/start-time")
    public ContestResponse changeStartTime(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                           @Valid @RequestBody ChangeStartTimeRequest request) {
        return service.changeStartTime(actor, id, request);
    }

    @PostMapping("/contests/{id}/cancel")
    public ContestResponse cancel(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                  @Valid @RequestBody(required = false) CancelContestRequest request) {
        return service.cancel(actor, id, request == null ? null : request.reason());
    }
}
