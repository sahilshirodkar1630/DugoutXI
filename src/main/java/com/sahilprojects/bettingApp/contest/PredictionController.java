package com.sahilprojects.bettingApp.contest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.contest.dto.MyPredictionResponse;
import com.sahilprojects.bettingApp.contest.dto.PredictionRequest;
import com.sahilprojects.bettingApp.contest.dto.PredictionRowResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Picks")
@RestController
@RequestMapping("/api/contests/{contestId}/predictions")
public class PredictionController {

    private final PredictionService service;

    public PredictionController(PredictionService service) {
        this.service = service;
    }

    /** Make or change my pick. Allowed until the contest starts. */
    @Operation(summary = "Pick the winner",
            description = "Confirmed players only, and only before the start time. You can change your pick until then.")
    @PutMapping("/me")
    public MyPredictionResponse predict(@AuthenticationPrincipal AuthenticatedUser actor,
                                        @PathVariable Long contestId,
                                        @Valid @RequestBody PredictionRequest request) {
        return service.predict(actor, contestId, request.teamId());
    }

    @Operation(summary = "Your pick")
    @GetMapping("/me")
    public MyPredictionResponse myPrediction(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @PathVariable Long contestId) {
        return service.myPrediction(actor, contestId);
    }

    /** Everyone's picks. Refused until the contest starts. */
    @Operation(summary = "Everyone's picks",
            description = "Empty until the contest has started, so nobody can copy a pick.")
    @GetMapping
    public List<PredictionRowResponse> all(@AuthenticationPrincipal AuthenticatedUser actor,
                                           @PathVariable Long contestId) {
        return service.allPredictions(actor, contestId);
    }
}
