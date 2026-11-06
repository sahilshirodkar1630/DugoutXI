package com.sahilprojects.bettingApp.jobs;

import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.league.LeagueRepository;
import com.sahilprojects.bettingApp.league.LeagueStatus;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.settlement.SettlementRow;
import com.sahilprojects.bettingApp.settlement.SettlementRowRepository;
import com.sahilprojects.bettingApp.settlement.SettlementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A daily nudge to anyone who still owes money, or is still owed it, in a league that has ended.
 *
 * Settling happens outside the app, so the only thing the app can usefully do is keep asking. The reminder is dated
 * so each day's nudge is a new one rather than a duplicate that gets dropped, and it stops as soon as the row is
 * marked settled.
 */
@Component
public class SettlementReminderJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementReminderJob.class);

    /** Nothing is sent for the first day: people need a chance to settle before being chased. */
    private static final Duration GRACE = Duration.ofDays(1);

    private final LeagueRepository leagues;
    private final SettlementRowRepository rows;
    private final NotificationService notificationService;
    private final Clock clock;

    public SettlementReminderJob(LeagueRepository leagues, SettlementRowRepository rows,
                                 NotificationService notificationService, Clock clock) {
        this.leagues = leagues;
        this.rows = rows;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Transactional
    public int run() {
        Instant now = clock.instant();
        String today = now.toString().substring(0, 10);           // one nudge per person per league per day
        int sent = 0;

        for (League league : leagues.findByStatus(LeagueStatus.PENDING_SETTLEMENT)) {
            if (league.getEndedAt() != null && league.getEndedAt().plus(GRACE).isAfter(now)) {
                continue;
            }
            List<SettlementRow> pending = rows.findByLeague_IdAndSettlementStatus(
                    league.getId(), SettlementStatus.PENDING);
            for (SettlementRow row : pending) {
                // Everyone paid the whole season up front, so a pending row is always money owed back to the member.
                notificationService.notifyOnce(row.getUser().getId(), NotificationType.SETTLEMENT_UPDATED,
                        ReferenceType.LEAGUE, league.getId(),
                        "Still to settle in " + league.getName(),
                        "The organizer still has to return " + row.getFinalBalance().toPlainString() + " to you.",
                        "settle-" + today);
                sent++;
            }
            // The organizer is chased too, since they are the one doing the paying and the ticking off.
            if (!pending.isEmpty()) {
                notificationService.notifyOnce(league.getOrganizer().getId(), NotificationType.SETTLEMENT_UPDATED,
                        ReferenceType.LEAGUE, league.getId(),
                        league.getName() + " is still settling up",
                        pending.size() + " member(s) are not marked as settled yet.",
                        "settle-organizer-" + today);
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Settlement reminders queued: {}", sent);
        }
        return sent;
    }
}
