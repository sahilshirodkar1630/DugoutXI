package com.sahilprojects.bettingApp.contest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.contest.dto.AcceptParticipantRequest;
import com.sahilprojects.bettingApp.contest.dto.ParticipantResponse;
import com.sahilprojects.bettingApp.contest.dto.ReverseParticipantRequest;
import com.sahilprojects.bettingApp.league.dto.ReasonRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Joining somebody else's standalone contest, and the organizer's side of that. */
@Tag(name = "Contest players")
@RestController
@RequestMapping("/api/contests/{contestId}")
public class ContestMembershipController {

    private final ContestMembershipService service;

    public ContestMembershipController(ContestMembershipService service) {
        this.service = service;
    }

    // ---------- the user ----------

    @Operation(summary = "Ask for a place in a one-off contest",
            description = "Before its registration deadline. The organizer lets people in one at a time.")
    @PostMapping("/join-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantResponse requestToJoin(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @PathVariable Long contestId) {
        return service.requestToJoin(actor, contestId);
    }

    @Operation(summary = "Withdraw your request")
    @DeleteMapping("/join-requests/me")
    public ParticipantResponse withdraw(@AuthenticationPrincipal AuthenticatedUser actor,
                                        @PathVariable Long contestId) {
        return service.withdraw(actor, contestId);
    }

    // ---------- reading ----------

    @Operation(summary = "Players in a one-off contest",
            description = "Optionally filtered by `status`.")
    @GetMapping("/participants")
    public List<ParticipantResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,
                                          @PathVariable Long contestId,
                                          @RequestParam(required = false) ParticipantStatus status) {
        return service.list(actor, contestId, status);
    }

    // ---------- the organizer ----------

    @Operation(summary = "Let someone in",
            description = "Organizer of this league or contest, or an admin with a reason. Send `paid: true` if they have already paid.")
    @PostMapping("/participants/{participantId}/accept")
    public ParticipantResponse accept(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long contestId,
                                      @PathVariable Long participantId,
                                      @Valid @RequestBody(required = false) AcceptParticipantRequest request) {
        return service.accept(actor, contestId, participantId,
                request == null ? new AcceptParticipantRequest(false, null) : request);
    }

    @Operation(summary = "Confirm a player has paid",
            description = "Organizer of this league or contest, or an admin with a reason.")
    @PostMapping("/participants/{participantId}/confirm-payment")
    public ParticipantResponse confirmPayment(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable Long contestId,
                                              @PathVariable Long participantId,
                                              @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.confirmPayment(actor, contestId, participantId, request == null ? null : request.reason());
    }

    @Operation(summary = "Turn down a request",
            description = "Organizer of this league or contest, or an admin with a reason.")
    @PostMapping("/participants/{participantId}/reject")
    public ParticipantResponse reject(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long contestId,
                                      @PathVariable Long participantId,
                                      @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.reject(actor, contestId, participantId, request == null ? null : request.reason());
    }

    @Operation(summary = "Take a player out",
            description = "Organizer of this league or contest, or an admin with a reason.")
    @PostMapping("/participants/{participantId}/remove")
    public ParticipantResponse remove(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long contestId,
                                      @PathVariable Long participantId,
                                      @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.remove(actor, contestId, participantId, request == null ? null : request.reason());
    }

    /** Undoes a confirmed place, because the money was handed back outside the app. */
    @Operation(summary = "Undo a confirmed place",
            description = "Organizer of this league or contest, or an admin with a reason. For when the money was handed back outside the app. Needs a reason.")
    @PostMapping("/participants/{participantId}/reverse")
    public ParticipantResponse reverse(@AuthenticationPrincipal AuthenticatedUser actor,
                                       @PathVariable Long contestId,
                                       @PathVariable Long participantId,
                                       @Valid @RequestBody ReverseParticipantRequest request) {
        return service.reverse(actor, contestId, participantId, request);
    }
}
