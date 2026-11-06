package com.sahilprojects.bettingApp.jobs;

import com.sahilprojects.bettingApp.league.LeagueService;
import com.sahilprojects.bettingApp.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock-driven half of the app. Everything here only moves things along at the time they were always going to
 * move: joining opens, unpaid places are cut off, contests lock, queued notifications go out, and people are
 * reminded to settle.
 *
 * Each job locks the rows it works on with skip-locked, so this is safe to leave running on more than one instance.
 * Switch the lot off with {@code app.jobs.enabled=false}, which is what the tests do so that a background run
 * cannot change a fixture halfway through a test.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private static final int NOTIFICATION_BATCH = 200;

    private final LeagueService leagueService;
    private final PaymentCutoffJob paymentCutoff;
    private final ContestStartJob contestStart;
    private final SettlementReminderJob settlementReminder;
    private final NotificationService notificationService;

    public ScheduledJobs(LeagueService leagueService, PaymentCutoffJob paymentCutoff, ContestStartJob contestStart,
                         SettlementReminderJob settlementReminder, NotificationService notificationService) {
        this.leagueService = leagueService;
        this.paymentCutoff = paymentCutoff;
        this.contestStart = contestStart;
        this.settlementReminder = settlementReminder;
        this.notificationService = notificationService;
        log.info("Scheduled jobs are enabled");
    }

    /** Opens leagues whose registration start has arrived. */
    @Scheduled(fixedDelayString = "${app.jobs.open-leagues-ms:60000}")
    public void openDueLeagues() {
        guard("open leagues", leagueService::openDueLeagues);
    }

    /** Removes anyone accepted but unpaid once their deadline has passed. */
    @Scheduled(fixedDelayString = "${app.jobs.payment-cutoff-ms:60000}")
    public void paymentCutoff() {
        guard("payment cutoff", paymentCutoff::run);
    }

    /**
     * The important one: locks contests whose start time has arrived, starts a league with its first contest,
     * and cancels anything that cannot go ahead. Runs often, because locking late means a late pick could land.
     */
    @Scheduled(fixedDelayString = "${app.jobs.contest-start-ms:20000}")
    public void startDueContests() {
        guard("contest start", contestStart::run);
    }

    /** Delivers reminders whose moment has come. */
    @Scheduled(fixedDelayString = "${app.jobs.notifications-ms:30000}")
    public void sendDueNotifications() {
        guard("notification sender", () -> notificationService.sendDue(NOTIFICATION_BATCH));
    }

    /** Chases whoever has not settled up, once a day. */
    @Scheduled(fixedDelayString = "${app.jobs.settlement-reminder-ms:86400000}")
    public void remindAboutSettlements() {
        guard("settlement reminder", settlementReminder::run);
    }

    /**
     * A job that throws must not kill its schedule: Spring stops rescheduling a fixed-delay task whose exception
     * escapes, and a single bad row would then silently switch that job off for the life of the process.
     */
    private void guard(String name, Work work) {
        try {
            int changed = work.run();
            if (changed > 0) {
                log.debug("Job '{}' changed {} row(s)", name, changed);
            }
        } catch (RuntimeException e) {
            log.error("Job '{}' failed and will run again on its next tick: {}", name, e.getMessage(), e);
        }
    }

    private interface Work {
        int run();
    }
}
