package com.sahilprojects.bettingApp.jobs;

import com.sahilprojects.bettingApp.contest.ContestParticipant;
import com.sahilprojects.bettingApp.contest.ContestParticipantRepository;
import com.sahilprojects.bettingApp.contest.ParticipantStatus;
import com.sahilprojects.bettingApp.league.LeagueMember;
import com.sahilprojects.bettingApp.league.LeagueMemberRepository;
import com.sahilprojects.bettingApp.league.LeagueMemberStatus;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.participation.ParticipationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Takes out anyone who was accepted but never paid, once their deadline has passed.
 *
 * This is the only fair way to work out a pool: every confirmed entry is money the organizer actually holds, so
 * somebody who never paid cannot be left in and counted. They are told why, and they can ask to join again if the
 * organizer moves the deadline.
 *
 * Rows another run has already picked up are skipped, so two instances running this at once is harmless.
 */
@Component
public class PaymentCutoffJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentCutoffJob.class);

    private final LeagueMemberRepository leagueMembers;
    private final ContestParticipantRepository participants;
    private final ParticipationService participation;
    private final NotificationService notificationService;
    private final Clock clock;

    public PaymentCutoffJob(LeagueMemberRepository leagueMembers, ContestParticipantRepository participants,
                            ParticipationService participation, NotificationService notificationService, Clock clock) {
        this.leagueMembers = leagueMembers;
        this.participants = participants;
        this.participation = participation;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /** League members and standalone participants in one pass. Returns how many were taken out. */
    @Transactional
    public int run() {
        return cutoffLeagueMembers() + cutoffStandaloneParticipants();
    }

    @Transactional
    public int cutoffLeagueMembers() {
        List<LeagueMember> overdue = leagueMembers.lockUnpaidPastDeadline(
                LeagueMemberStatus.ACCEPTED_UNPAID, clock.instant());
        for (LeagueMember member : overdue) {
            participation.cutoffUnpaid(member);
            notificationService.notifyNow(member.getUser().getId(), NotificationType.REMOVED_FOR_NON_PAYMENT,
                    ReferenceType.LEAGUE, member.getLeague().getId(),
                    "You were removed from " + member.getLeague().getName(),
                    "The payment deadline passed before your joining amount arrived. "
                            + "Ask the organizer if you would still like to play.");
        }
        if (!overdue.isEmpty()) {
            log.info("Payment cutoff removed {} league member(s)", overdue.size());
        }
        return overdue.size();
    }

    @Transactional
    public int cutoffStandaloneParticipants() {
        List<ContestParticipant> overdue = participants.lockUnpaidPastDeadline(
                ParticipantStatus.ACCEPTED_UNPAID, clock.instant());
        for (ContestParticipant row : overdue) {
            participation.cutoffUnpaid(row);
            notificationService.notifyNow(row.getUser().getId(), NotificationType.REMOVED_FOR_NON_PAYMENT,
                    ReferenceType.CONTEST, row.getContest().getId(),
                    "You were removed from a contest",
                    "The payment deadline passed before your entry arrived.");
        }
        if (!overdue.isEmpty()) {
            log.info("Payment cutoff removed {} standalone participant(s)", overdue.size());
        }
        return overdue.size();
    }
}
