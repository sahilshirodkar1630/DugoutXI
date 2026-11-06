package com.sahilprojects.bettingApp.payout;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.payout.dto.PayoutResponse;
import com.sahilprojects.bettingApp.payout.dto.UpdatePayoutStatusRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/contests/{contestId}/payouts")
public class PayoutController {

    private final PayoutService service;

    public PayoutController(PayoutService service) {
        this.service = service;
    }

    /** The organizer sees everyone's payouts; a participant sees only their own. */
    @GetMapping
    public List<PayoutResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,
                                     @PathVariable Long contestId) {
        return service.list(actor, contestId);
    }

    /** Mark a payout as handed over, or take that back with a reason. */
    @PutMapping("/{payoutId}/status")
    public PayoutResponse updateStatus(@AuthenticationPrincipal AuthenticatedUser actor,
                                       @PathVariable Long contestId,
                                       @PathVariable Long payoutId,
                                       @Valid @RequestBody UpdatePayoutStatusRequest request) {
        return service.updateStatus(actor, contestId, payoutId, request.status(), request.reason());
    }
}
