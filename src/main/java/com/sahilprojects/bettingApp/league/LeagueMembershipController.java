package com.sahilprojects.bettingApp.league;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.league.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leagues/{leagueId}")
public class LeagueMembershipController {

    private final LeagueMembershipService service;

    public LeagueMembershipController(LeagueMembershipService service) {
        this.service = service;
    }

    // ---------- the user ----------

    /** Also used to ask again after a rejection or a withdrawal. */
    @PostMapping("/join-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse requestToJoin(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId) {
        return service.requestToJoin(actor, leagueId);
    }

    @DeleteMapping("/join-requests/me")
    public MemberResponse withdraw(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId) {
        return service.withdraw(actor, leagueId);
    }

    // ---------- reading ----------

    @GetMapping("/members")
    public List<MemberResponse> members(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                        @RequestParam(required = false) LeagueMemberStatus status) {
        return service.listMembers(actor, leagueId, status);
    }

    // ---------- the organizer, or an admin with a reason ----------

    @PostMapping("/members/{memberId}/accept")
    public MemberResponse accept(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                 @PathVariable Long memberId, @Valid @RequestBody AcceptMemberRequest request) {
        return service.accept(actor, leagueId, memberId, request);
    }

    @PostMapping("/members/{memberId}/confirm-payment")
    public MemberResponse confirmPayment(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                         @PathVariable Long memberId,
                                         @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.confirmPayment(actor, leagueId, memberId, request == null ? null : request.reason());
    }

    @PostMapping("/members/{memberId}/reject")
    public MemberResponse reject(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                 @PathVariable Long memberId,
                                 @Valid @RequestBody(required = false) ReasonRequest request) {
        return service.reject(actor, leagueId, memberId, request == null ? null : request.reason());
    }

    @PostMapping("/members/{memberId}/remove")
    public MemberResponse remove(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                 @PathVariable Long memberId, @Valid @RequestBody RemoveMemberRequest request) {
        return service.remove(actor, leagueId, memberId, request.reason());
    }

    @PostMapping("/members/{memberId}/reverse")
    public MemberResponse reverse(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId,
                                  @PathVariable Long memberId, @Valid @RequestBody ReverseMemberRequest request) {
        return service.reverse(actor, leagueId, memberId, request);
    }
}
