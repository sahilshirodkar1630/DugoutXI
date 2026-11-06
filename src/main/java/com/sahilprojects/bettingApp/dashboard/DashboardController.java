package com.sahilprojects.bettingApp.dashboard;

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
    @GetMapping("/leagues/{leagueId}/board")
    public LeagueBoardResponse board(@AuthenticationPrincipal AuthenticatedUser actor,
                                     @PathVariable Long leagueId) {
        return service.leagueBoard(actor, leagueId);
    }

    @GetMapping("/leagues/{leagueId}/board/me")
    public LeagueBoardRowResponse myLine(@AuthenticationPrincipal AuthenticatedUser actor,
                                         @PathVariable Long leagueId) {
        return service.myLeagueLine(actor, leagueId);
    }

    /** My record across every league and standalone contest. */
    @GetMapping("/me/stats")
    public MyStatsResponse myStats(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.myStats(actor);
    }

    /** My record over a period: ONE_MONTH, THREE_MONTHS, SIX_MONTHS, ONE_YEAR or ALL (the default). */
    @GetMapping("/me/stats/period")
    public com.sahilprojects.bettingApp.dashboard.dto.PeriodStatsResponse periodStats(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "ALL") StatsRange range) {
        return service.periodStats(actor, range);
    }

    /** The home screen: my record, what I am owed, what I owe, and what is waiting on me. */
    @GetMapping("/me/summary")
    public MySummaryResponse mySummary(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.mySummary(actor);
    }
}
