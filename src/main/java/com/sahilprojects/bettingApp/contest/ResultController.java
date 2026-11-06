package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.contest.dto.DeclareResultRequest;
import com.sahilprojects.bettingApp.contest.dto.ParticipantOutcomeResponse;
import com.sahilprojects.bettingApp.contest.dto.ResultPreviewResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/contests/{contestId}")
public class ResultController {

    private final ResultService service;

    public ResultController(ResultService service) {
        this.service = service;
    }

    /** Step one: see exactly what this result would pay, without saving it. */
    @PostMapping("/result/preview")
    public ResultPreviewResponse preview(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long contestId,
                                         @Valid @RequestBody DeclareResultRequest request) {
        return service.preview(actor, contestId, request);
    }

    /** Step two: save it. Send back the previewToken from the preview. */
    @PostMapping("/result/confirm")
    public ResultPreviewResponse confirm(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long contestId,
                                         @Valid @RequestBody DeclareResultRequest request) {
        return service.confirm(actor, contestId, request);
    }

    /** Replace a result that was entered wrong. A reason is required. */
    @PostMapping("/result/correct")
    public ResultPreviewResponse correct(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long contestId,
                                         @Valid @RequestBody DeclareResultRequest request) {
        return service.correct(actor, contestId, request);
    }

    /** Who won what, once the result is in. */
    @GetMapping("/outcomes")
    public List<ParticipantOutcomeResponse> outcomes(@AuthenticationPrincipal AuthenticatedUser actor,
                                                     @PathVariable Long contestId) {
        return service.outcomes(actor, contestId);
    }
}
