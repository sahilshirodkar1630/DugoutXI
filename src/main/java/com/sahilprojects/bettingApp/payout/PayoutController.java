package com.sahilprojects.bettingApp.payout;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.payout.dto.PayoutResponse;
import com.sahilprojects.bettingApp.payout.dto.UpdatePayoutStatusRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Payouts")
@RestController
@RequestMapping("/api/contests/{contestId}/payouts")
public class PayoutController {

    private final PayoutService service;

    public PayoutController(PayoutService service) {
        this.service = service;
    }

    /** The organizer sees everyone's payouts; a participant sees only their own. */
    @Operation(summary = "Who is owed what in a one-off contest",
            description = "Winnings and refunds. The organizer's own payout is marked paid at once: they hold the pot.")
    @GetMapping
    public List<PayoutResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,
                                     @PathVariable Long contestId) {
        return service.list(actor, contestId);
    }

    /** Mark a payout as handed over, or take that back with a reason. */
    @Operation(summary = "Mark a payout paid (or not)",
            description = "Organizer of this league or contest, or an admin with a reason. The money itself moves outside the app.")
    @PutMapping("/{payoutId}/status")
    public PayoutResponse updateStatus(@AuthenticationPrincipal AuthenticatedUser actor,
                                       @PathVariable Long contestId,
                                       @PathVariable Long payoutId,
                                       @Valid @RequestBody UpdatePayoutStatusRequest request) {
        return service.updateStatus(actor, contestId, payoutId, request.status(), request.reason());
    }
}
