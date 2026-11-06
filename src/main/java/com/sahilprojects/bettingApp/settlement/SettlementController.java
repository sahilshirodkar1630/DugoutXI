package com.sahilprojects.bettingApp.settlement;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.league.dto.ReasonRequest;
import com.sahilprojects.bettingApp.settlement.dto.SettlementRowResponse;
import com.sahilprojects.bettingApp.settlement.dto.SettlementSummaryResponse;
import com.sahilprojects.bettingApp.settlement.dto.UpdateSettlementStatusRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Ending a league and settling up. */
@RestController
@RequestMapping("/api/leagues/{leagueId}")
public class SettlementController {

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    /** Ends the league and works out everybody's closing balance. */
    @PostMapping("/end")
    public SettlementSummaryResponse end(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long leagueId,
                                         @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.end(actor, leagueId, request == null ? null : request.reason());
    }

    @GetMapping("/settlement")
    public List<SettlementRowResponse> rows(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable Long leagueId) {
        return service.list(actor, leagueId);
    }

    /** Who is owed, who owes, and whether the figures add up. */
    @GetMapping("/settlement/summary")
    public SettlementSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @PathVariable Long leagueId) {
        return service.summary(actor, leagueId);
    }

    @PutMapping("/settlement/{rowId}/status")
    public SettlementRowResponse updateStatus(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable Long leagueId,
                                              @PathVariable Long rowId,
                                              @Valid @RequestBody UpdateSettlementStatusRequest request) {
        return service.updateStatus(actor, leagueId, rowId, request.status(), request.reason());
    }

    /** Closes the league once everybody has settled. */
    @PostMapping("/complete")
    public SettlementSummaryResponse complete(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable Long leagueId,
                                              @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.complete(actor, leagueId, request == null ? null : request.reason());
    }

    /** Admin only: writes off whatever is still outstanding so a stuck league can be closed. */
    @PostMapping("/force-complete")
    public SettlementSummaryResponse forceComplete(@AuthenticationPrincipal AuthenticatedUser actor,
                                                   @PathVariable Long leagueId,
                                                   @Valid @RequestBody ReasonRequest request) {
        return service.forceComplete(actor, leagueId, request.reason());
    }
}
