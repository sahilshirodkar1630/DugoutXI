package com.sahilprojects.bettingApp.dashboard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.dashboard.dto.LeagueBoardResponse;
import com.sahilprojects.bettingApp.dashboard.dto.LeagueBoardRowResponse;
import com.sahilprojects.bettingApp.dashboard.dto.MyStatsResponse;
import com.sahilprojects.bettingApp.dashboard.dto.MySummaryResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Everything the app reads rather than changes. */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /** The league table, ranked by profit and loss. */
    @Operation(summary = "League table",
            description = "Ranked by net profit. Members only.")
    @Tag(name = "Board")
    @GetMapping("/leagues/{leagueId}/board")
    public LeagueBoardResponse board(@AuthenticationPrincipal AuthenticatedUser actor,
                                     @PathVariable Long leagueId) {
        return service.leagueBoard(actor, leagueId);
    }

    @Operation(summary = "Your line on the league table")
    @Tag(name = "Board")
    @GetMapping("/leagues/{leagueId}/board/me")
    public LeagueBoardRowResponse myLine(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long leagueId) {
        return service.myLeagueLine(actor, leagueId);
    }

    /** My record across every league and standalone contest. */
    @Operation(summary = "Your all-time record")
    @Tag(name = "Me")
    @GetMapping("/me/stats")
    public MyStatsResponse myStats(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.myStats(actor);
    }

    /** My record over a period: ONE_MONTH, THREE_MONTHS, SIX_MONTHS, ONE_YEAR or ALL (the default). */
    @Operation(summary = "Your record for a period",
            description = "`range` is ONE_MONTH, THREE_MONTHS, SIX_MONTHS, ONE_YEAR or ALL (the default). Contests count by start time; months are in India time.")
    @Tag(name = "Me")
    @GetMapping("/me/stats/period")
    public com.sahilprojects.bettingApp.dashboard.dto.PeriodStatsResponse periodStats(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "ALL") StatsRange range) {
        return service.periodStats(actor, range);
    }

    /** The home screen: my record, what I am owed, what I owe, and what is waiting on me. */
    @Operation(summary = "Home screen summary",
            description = "Your record, money coming back to you, and what is waiting on you.")
    @Tag(name = "Board")
    @GetMapping("/me/summary")
    public MySummaryResponse mySummary(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.mySummary(actor);
    }
}
