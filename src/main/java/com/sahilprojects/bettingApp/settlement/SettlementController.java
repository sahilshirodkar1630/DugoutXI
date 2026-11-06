package com.sahilprojects.bettingApp.settlement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Settlement")
@RestController
@RequestMapping("/api/leagues/{leagueId}")
public class SettlementController {

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    /** Ends the league and works out everybody's closing balance. */
    @Operation(summary = "End a league",
            description = "Organizer of this league or contest, or an admin with a reason. Every member's final balance is worked out and frozen: `final_balance = joining_amount_paid + net_pl`, which the organizer hands back in full. Every started match must have a result.")
    @PostMapping("/end")
    public SettlementSummaryResponse end(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long leagueId,
                                         @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.end(actor, leagueId, request == null ? null : request.reason());
    }

    @Operation(summary = "Settlement rows",
            description = "One per member: what the organizer hands back, and whether it has been.")
    @GetMapping("/settlement")
    public List<SettlementRowResponse> rows(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable Long leagueId) {
        return service.list(actor, leagueId);
    }

    /** Who is owed, who owes, and whether the figures add up. */
    @Operation(summary = "Settlement summary",
            description = "Totals, and whether the books add up.")
    @GetMapping("/settlement/summary")
    public SettlementSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @PathVariable Long leagueId) {
        return service.summary(actor, leagueId);
    }

    @Operation(summary = "Mark a member settled (or not)",
            description = "Organizer of this league or contest, or an admin with a reason.")
    @PutMapping("/settlement/{rowId}/status")
    public SettlementRowResponse updateStatus(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable Long leagueId,
                                              @PathVariable Long rowId,
                                              @Valid @RequestBody UpdateSettlementStatusRequest request) {
        return service.updateStatus(actor, leagueId, rowId, request.status(), request.reason());
    }

    /** Closes the league once everybody has settled. */
    @Operation(summary = "Close the league",
            description = "Organizer of this league or contest, or an admin with a reason. Once every row is settled.")
    @PostMapping("/complete")
    public SettlementSummaryResponse complete(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable Long leagueId,
                                              @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.complete(actor, leagueId, request == null ? null : request.reason());
    }

    /** Admin only: writes off whatever is still outstanding so a stuck league can be closed. */
    @Operation(summary = "Close the league with rows outstanding",
            description = "Admin only, with a reason. Outstanding rows are written off.")
    @PostMapping("/force-complete")
    public SettlementSummaryResponse forceComplete(@AuthenticationPrincipal AuthenticatedUser actor,
                                                   @PathVariable Long leagueId,
                                                   @Valid @RequestBody ReasonRequest request) {
        return service.forceComplete(actor, leagueId, request.reason());
    }
}
