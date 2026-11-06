package com.sahilprojects.bettingApp.contest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.contest.dto.DeclareResultRequest;
import com.sahilprojects.bettingApp.contest.dto.ParticipantOutcomeResponse;
import com.sahilprojects.bettingApp.contest.dto.ResultPreviewResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Results")
@RestController
@RequestMapping("/api/contests/{contestId}")
public class ResultController {

    private final ResultService service;

    public ResultController(ResultService service) {
        this.service = service;
    }

    /** Step one: see exactly what this result would pay, without saving it. */
    @Operation(summary = "Preview a result (step 1 of 2)",
            description = "Organizer of this league or contest, or an admin with a reason. Send `winningTeamId`, or `noResult: true`. Returns who wins what and a `previewToken` derived from the result and every pick.")
    @PostMapping("/result/preview")
    public ResultPreviewResponse preview(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long contestId,
                                         @Valid @RequestBody DeclareResultRequest request) {
        return service.preview(actor, contestId, request);
    }

    /** Step two: save it. Send back the previewToken from the preview. */
    @Operation(summary = "Confirm a result (step 2 of 2)",
            description = "Send the same result with the `previewToken`. Refused (409) if any pick changed since the preview. One-off contests settle at once through payouts.")
    @PostMapping("/result/confirm")
    public ResultPreviewResponse confirm(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long contestId,
                                         @Valid @RequestBody DeclareResultRequest request) {
        return service.confirm(actor, contestId, request);
    }

    /** Replace a result that was entered wrong. A reason is required. */
    @Operation(summary = "Correct a declared result",
            description = "Organizer of this league or contest, or an admin with a reason. Needs a `reason`, which goes in the audit log; no preview token. Refused once money has moved: a one-off payout marked paid to someone other than the organizer, or a league member other than the organizer settled.")
    @PostMapping("/result/correct")
    public ResultPreviewResponse correct(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long contestId,
                                         @Valid @RequestBody DeclareResultRequest request) {
        return service.correct(actor, contestId, request);
    }

    /** Who won what, once the result is in. */
    @Operation(summary = "What each player got",
            description = "Won, lost or refunded, with the amounts.")
    @GetMapping("/outcomes")
    public List<ParticipantOutcomeResponse> outcomes(@AuthenticationPrincipal AuthenticatedUser actor,
                                                     @PathVariable Long contestId) {
        return service.outcomes(actor, contestId);
    }
}
