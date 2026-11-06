package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.league.LeagueMember;
import com.sahilprojects.bettingApp.league.LeagueMemberRepository;
import com.sahilprojects.bettingApp.league.LeagueMemberStatus;
import com.sahilprojects.bettingApp.participation.ParticipationConfirmationChanged;
import com.sahilprojects.bettingApp.participation.ParticipationKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps contest_participants in step with league membership.
 *
 * League contests: every CONFIRMED league member is a participant, with no separate payment (the league's joining
 * amount covers it). A contest gets its participants when it is created. A member who is confirmed later is added to
 * every contest that has not started, and a member who stops being confirmed is taken out of those. Contests that
 * have started are never touched.
 *
 * It listens for ParticipationConfirmationChanged, which is published inside the same transaction as the status change,
 * so a member's confirmation and their enrolment succeed or fail together.
 */
@Service
public class EnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrolmentService.class);

    private final ContestParticipantRepository participants;
    private final LeagueMemberRepository leagueMembers;
    private final ContestRepository contests;
    private final Clock clock;

    public EnrolmentService(ContestParticipantRepository participants, LeagueMemberRepository leagueMembers,
                            ContestRepository contests, Clock clock) {
        this.participants = participants;
        this.leagueMembers = leagueMembers;
        this.contests = contests;
        this.clock = clock;
    }

    /** A brand new league contest: enrol every CONFIRMED member. Returns how many were enrolled. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enrolLeagueMembers(Contest contest) {
        List<LeagueMember> confirmed = leagueMembers.findByLeague_IdAndLeagueMemberStatus(
                contest.getLeague().getId(), LeagueMemberStatus.CONFIRMED);
        Instant now = clock.instant();
        List<ContestParticipant> rows = new ArrayList<>(confirmed.size());
        for (LeagueMember member : confirmed) {
            rows.add(newLeagueParticipant(contest, member, now));
        }
        participants.saveAll(rows);
        return rows.size();
    }

    /**
     * A member became CONFIRMED: enrol them in every league contest that has not started.
     * A row left from an earlier removal is reused (there is one row per user per contest).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enrolMember(LeagueMember member) {
        Instant now = clock.instant();
        int changed = 0;
        for (Contest contest : contests.findUnstartedByLeague(member.getLeague().getId(), ContestStatus.SCHEDULED, now)) {
            Optional<ContestParticipant> existing =
                    participants.findByContest_IdAndUser_Id(contest.getId(), member.getUser().getId());
            if (existing.isEmpty()) {
                participants.save(newLeagueParticipant(contest, member, now));
                changed++;
            } else if (existing.get().getParticipantStatus() != ParticipantStatus.CONFIRMED) {
                ContestParticipant row = existing.get();
                row.setParticipantStatus(ParticipantStatus.CONFIRMED);
                row.setLeagueMember(member);
                row.setConfirmedAt(now);
                row.setRemovedAt(null);
                row.setStatusReason(null);
                changed++;
            }
        }
        return changed;
    }

    /** A member stopped being CONFIRMED: take them out of every league contest that has not started. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int removeMember(LeagueMember member) {
        Instant now = clock.instant();
        int changed = 0;
        for (ContestParticipant row : participants.findInUnstartedContests(member.getId(), ContestStatus.SCHEDULED, now)) {
            if (row.getParticipantStatus() == ParticipantStatus.CONFIRMED) {
                row.setParticipantStatus(ParticipantStatus.REMOVED);
                row.setRemovedAt(now);
                row.setStatusReason("Removed from the league");
                row.setSelectedTeam(null);                 // an old pick must not linger on a removed row
                row.setPredictedAt(null);
                changed++;
            }
        }
        return changed;
    }

    /** Runs inside the transaction that changed the member's status, so both succeed or both roll back. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onConfirmationChanged(ParticipationConfirmationChanged event) {
        if (event.kind() != ParticipationKind.LEAGUE_MEMBER) {
            return;                                        // standalone participants are not tied to a league
        }
        LeagueMember member = leagueMembers.findById(event.participationId()).orElse(null);
        if (member == null) {
            log.warn("Confirmation event for unknown league member {}", event.participationId());
            return;
        }
        int changed = event.confirmed() ? enrolMember(member) : removeMember(member);
        log.debug("League member {} {}: {} contest(s) changed", member.getId(),
                event.confirmed() ? "confirmed" : "no longer confirmed", changed);
    }

    private static ContestParticipant newLeagueParticipant(Contest contest, LeagueMember member, Instant now) {
        ContestParticipant row = new ContestParticipant();
        row.setContest(contest);
        row.setUser(member.getUser());
        row.setLeagueMember(member);
        row.setParticipantStatus(ParticipantStatus.CONFIRMED);
        row.setPaymentStatus(ContestPaymentStatus.NOT_REQUIRED);   // paid through the league's joining amount
        row.setConfirmedAt(now);
        return row;
    }
}
