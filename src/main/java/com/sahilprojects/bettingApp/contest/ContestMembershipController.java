package com.sahilprojects.bettingApp.contest;

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
@RestController
@RequestMapping("/api/contests/{contestId}")
public class ContestMembershipController {

    private final ContestMembershipService service;

    public ContestMembershipController(ContestMembershipService service) {
        this.service = service;
    }

    // ---------- the user ----------

    @PostMapping("/join-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantResponse requestToJoin(@AuthenticationPrincipal AuthenticatedUser actor,
                                             @PathVariable Long contestId) {
        return service.requestToJoin(actor, contestId);
    }

    @DeleteMapping("/join-requests/me")
    public ParticipantResponse withdraw(@AuthenticationPrincipal AuthenticatedUser actor,
                                        @PathVariable Long contestId) {
        return service.withdraw(actor, contestId);
    }

    // ---------- reading ----------

    @GetMapping("/participants")
    public List<ParticipantResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,
                                          @PathVariable Long contestId,
                                          @RequestParam(required = false) ParticipantStatus status) {
        return service.list(actor, contestId, status);
    }

    // ---------- the organizer ----------

    @PostMapping("/participants/{participantId}/accept")
    public ParticipantResponse accept(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long contestId,
                                      @PathVariable Long participantId,
                                      @Valid @RequestBody(required = false) AcceptParticipantRequest request) {
        return service.accept(actor, contestId, participantId,
                request == null ? new AcceptParticipantRequest(false, null) : request);
    }

    @PostMapping("/participants/{participantId}/confirm-payment")
    public ParticipantResponse confirmPayment(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @PathVariable Long contestId,
                                              @PathVariable Long participantId,
                                              @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.confirmPayment(actor, contestId, participantId, request == null ? null : request.reason());
    }

    @PostMapping("/participants/{participantId}/reject")
    public ParticipantResponse reject(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long contestId,
                                      @PathVariable Long participantId,
                                      @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.reject(actor, contestId, participantId, request == null ? null : request.reason());
    }

    @PostMapping("/participants/{participantId}/remove")
    public ParticipantResponse remove(@AuthenticationPrincipal AuthenticatedUser actor,
                                      @PathVariable Long contestId,
                                      @PathVariable Long participantId,
                                      @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.remove(actor, contestId, participantId, request == null ? null : request.reason());
    }

    /** Undoes a confirmed place, because the money was handed back outside the app. */
    @PostMapping("/participants/{participantId}/reverse")
    public ParticipantResponse reverse(@AuthenticationPrincipal AuthenticatedUser actor,
                                       @PathVariable Long contestId,
                                       @PathVariable Long participantId,
                                       @Valid @RequestBody ReverseParticipantRequest request) {
        return service.reverse(actor, contestId, participantId, request);
    }
}
