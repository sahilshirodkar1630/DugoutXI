package com.sahilprojects.bettingApp.league;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.league.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "League members")
@RestController
@RequestMapping("/api/leagues/{leagueId}")
public class LeagueMembershipController {

    private final LeagueMembershipService service;

    public LeagueMembershipController(LeagueMembershipService service) {
        this.service = service;
    }

    // ---------- the user ----------

    /** Also used to ask again after a rejection or a withdrawal. */
    @Operation(summary = "Ask to join a league",
            description = "While it is OPEN and joining has not closed. The organizer accepts or turns you down.")
    @PostMapping("/join-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse requestToJoin(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId) {
        return service.requestToJoin(actor, leagueId);
    }

    @Operation(summary = "Withdraw your request to join",
            description = "Only while it is still waiting or accepted but unpaid.")
    @DeleteMapping("/join-requests/me")
    public MemberResponse withdraw(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId) {
        return service.withdraw(actor, leagueId);
    }

    // ---------- reading ----------

    @Operation(summary = "League members",
            description = "Optionally filtered by `status`. Phone and UPI id are only shown to the organizer.")
    @GetMapping("/members")
    public List<MemberResponse> members(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                        @RequestParam(required = false) LeagueMemberStatus status) {
        return service.listMembers(actor, leagueId, status);
    }

    // ---------- the organizer, or an admin with a reason ----------

    @Operation(summary = "Accept a request to join",
            description = "Organizer of this league or contest, or an admin with a reason. Send `paid: true` if they have already paid, which confirms them in one step.")
    @PostMapping("/members/{memberId}/accept")
    public MemberResponse accept(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                 @PathVariable Long memberId, @Valid @RequestBody AcceptMemberRequest request) {
        return service.accept(actor, leagueId, memberId, request);
    }

    @Operation(summary = "Confirm a member has paid",
            description = "Organizer of this league or contest, or an admin with a reason. Confirmed members are in every match of the league.")
    @PostMapping("/members/{memberId}/confirm-payment")
    public MemberResponse confirmPayment(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                         @PathVariable Long memberId,
                                         @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.confirmPayment(actor, leagueId, memberId, request == null ? null : request.reason());
    }

    @Operation(summary = "Turn down a request to join",
            description = "Organizer of this league or contest, or an admin with a reason.")
    @PostMapping("/members/{memberId}/reject")
    public MemberResponse reject(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                 @PathVariable Long memberId,
                                 @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.reject(actor, leagueId, memberId, request == null ? null : request.reason());
    }

    @Operation(summary = "Take a member out",
            description = "Organizer of this league or contest, or an admin with a reason.")
    @PostMapping("/members/{memberId}/remove")
    public MemberResponse remove(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                 @PathVariable Long memberId, @Valid @RequestBody RemoveMemberRequest request) {
        return service.remove(actor, leagueId, memberId, request.reason());
    }

    @Operation(summary = "Undo a confirmed place",
            description = "Organizer of this league or contest, or an admin with a reason. For when the money was handed back outside the app. Needs a reason.")
    @PostMapping("/members/{memberId}/reverse")
    public MemberResponse reverse(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                  @PathVariable Long memberId, @Valid @RequestBody ReverseMemberRequest request) {
        return service.reverse(actor, leagueId, memberId, request);
    }
}
