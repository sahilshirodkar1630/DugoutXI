package com.sahilprojects.bettingApp.jobs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.contest.Contest;
import com.sahilprojects.bettingApp.contest.ContestRepository;
import com.sahilprojects.bettingApp.contest.ContestStatus;
import com.sahilprojects.bettingApp.league.LeagueService;
import com.sahilprojects.bettingApp.notification.NotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only shortcuts for trying the app out, off by default.
 *
 * Several things in this app only happen when a time arrives: a contest locks at its start time, a league starts
 * with its first contest, unpaid places are cut off at their deadline. Waiting for those while clicking through the
 * app is tedious, so these endpoints pull a start time forward and run the jobs on demand.
 *
 * Turn them on with {@code app.dev-tools.enabled=true}. They change data, so they are admin-only and should never
 * be enabled on anything real.
 */
@Tag(name = "Dev tools")
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "app.dev-tools.enabled", havingValue = "true")
public class DevToolsController {

    private final ContestRepository contests;
    private final ContestStartJob contestStart;
    private final PaymentCutoffJob paymentCutoff;
    private final SettlementReminderJob settlementReminder;
    private final NotificationService notificationService;
    private final LeagueService leagueService;
    private final AccessGuard accessGuard;
    private final Clock clock;
    /**
     * Explicit transactions, because startNow calls fastForward on this object: a call on `this` skips the Spring
     * proxy, so an @Transactional on fastForward would silently do nothing there.
     */
    private final TransactionTemplate tx;

    public DevToolsController(ContestRepository contests, ContestStartJob contestStart,
                              PaymentCutoffJob paymentCutoff, SettlementReminderJob settlementReminder,
                              NotificationService notificationService, LeagueService leagueService,
                              AccessGuard accessGuard, Clock clock, PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        this.contests = contests;
        this.contestStart = contestStart;
        this.paymentCutoff = paymentCutoff;
        this.settlementReminder = settlementReminder;
        this.notificationService = notificationService;
        this.leagueService = leagueService;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    /** Pulls a contest's start time back to a few seconds ago, so the start job will pick it up. */
    @Operation(summary = "Pull a contest's start time forward",
            description = "Admin only, and only when app.dev-tools.enabled is true.")
    @PostMapping("/contests/{contestId}/fast-forward")
    public Map<String, Object> fastForward(@AuthenticationPrincipal AuthenticatedUser actor,
                                           @PathVariable Long contestId) {
        accessGuard.requireAdmin(actor);
        return tx.execute(status -> {
            Contest contest = contests.findByIdForUpdate(contestId)
                    .orElseThrow(() -> new NotFoundException("Contest not found"));
            if (contest.getContestStatus() != ContestStatus.SCHEDULED) {
                return Map.<String, Object>of("contestId", contestId,
                        "contestStatus", contest.getContestStatus().name(), "changed", false);
            }
            Instant start = clock.instant().minus(Duration.ofSeconds(5));
            contest.setStartTime(start);
            // A standalone contest's deadlines may not be after its start, so they come back with it.
            if (contest.getPaymentDeadline() != null && contest.getPaymentDeadline().isAfter(start)) {
                contest.setPaymentDeadline(start);
            }
            if (contest.getRegistrationDeadline() != null && contest.getRegistrationDeadline().isAfter(start)) {
                contest.setRegistrationDeadline(start);
            }
            contests.saveAndFlush(contest);
            return Map.<String, Object>of("contestId", contestId, "startTime", start.toString(), "changed", true);
        });
    }

    /** Runs every scheduled job once, in the order the scheduler would. */
    @Operation(summary = "Run the scheduled jobs now",
            description = "Admin only, dev tools only.")
    @PostMapping("/jobs/run")
    public Map<String, Object> runJobs(@AuthenticationPrincipal AuthenticatedUser actor) {
        accessGuard.requireAdmin(actor);
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("leaguesOpened", leagueService.openDueLeagues());
        results.put("placesCutOff", paymentCutoff.run());
        results.put("contestsStarted", contestStart.run());
        results.put("notificationsSent", notificationService.sendDue(200));
        results.put("settlementRemindersQueued", settlementReminder.run());
        return results;
    }

    /** Locks one contest now, without waiting for the scheduler. */
    @Operation(summary = "Start (lock) a contest now",
            description = "Admin only, dev tools only.")
    @PostMapping("/contests/{contestId}/start-now")
    public Map<String, Object> startNow(@AuthenticationPrincipal AuthenticatedUser actor,
                                        @PathVariable Long contestId) {
        accessGuard.requireAdmin(actor);
        fastForward(actor, contestId);
        ContestStatus status = contestStart.startOne(contestId);
        return Map.of("contestId", contestId, "contestStatus", status == null ? "UNKNOWN" : status.name());
    }
}
