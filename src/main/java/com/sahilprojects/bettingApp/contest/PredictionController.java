package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.contest.dto.MyPredictionResponse;
import com.sahilprojects.bettingApp.contest.dto.PredictionRequest;
import com.sahilprojects.bettingApp.contest.dto.PredictionRowResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/contests/{contestId}/predictions")
public class PredictionController {

    private final PredictionService service;

    public PredictionController(PredictionService service) {
        this.service = service;
    }

    /** Make or change my pick. Allowed until the contest starts. */
    @PutMapping("/me")
    public MyPredictionResponse predict(@AuthenticationPrincipal AuthenticatedUser actor,
                                        @PathVariable Long contestId,
                                        @Valid @RequestBody PredictionRequest request) {
        return service.predict(actor, contestId, request.teamId());
    }

    @GetMapping("/me")
    public MyPredictionResponse myPrediction(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @PathVariable Long contestId) {
        return service.myPrediction(actor, contestId);
    }

    /** Everyone's picks. Refused until the contest starts. */
    @GetMapping
    public List<PredictionRowResponse> all(@AuthenticationPrincipal AuthenticatedUser actor,
                                           @PathVariable Long contestId) {
        return service.allPredictions(actor, contestId);
    }
}
