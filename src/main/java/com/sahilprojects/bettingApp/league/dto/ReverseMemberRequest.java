package com.sahilprojects.bettingApp.league.dto;

import com.sahilprojects.bettingApp.participation.ReverseTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Before the league starts: ACCEPTED_UNPAID (money returned, must pay again) or REMOVED (money returned).
 * After it starts, admin only and REMOVED only: the money stays and comes back through settlement.
 */
public record ReverseMemberRequest(@NotBlank @Size(max = 500) String reason, @NotNull ReverseTarget target) {
}
