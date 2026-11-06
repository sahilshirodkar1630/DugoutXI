package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.ForbiddenException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.contest.dto.MyPredictionResponse;
import com.sahilprojects.bettingApp.contest.dto.PredictionRowResponse;
import com.sahilprojects.bettingApp.contest.dto.TeamRef;
import com.sahilprojects.bettingApp.dashboard.DashboardQueryRepository;
import com.sahilprojects.bettingApp.dashboard.VisiblePredictionRow;
import com.sahilprojects.bettingApp.tournament.Team;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picking a winner, and seeing what everyone else picked.
 *
 * A pick can be made and changed freely until the contest starts, and not afterwards. The deadline is checked here
 * against the application clock, and again by a database trigger, so a request that slips through at the last
 * millisecond still cannot land a late pick.
 *
 * Other people's picks stay hidden until the contest starts. They are read through v_visible_predictions, which has
 * the same rule built into it, so even a mistake here cannot leak a pick early.
 */
@Service
public class PredictionService {

    private final ContestRepository contests;
    private final ContestParticipantRepository participants;
    private final DashboardQueryRepository dashboard;
    private final Clock clock;

    public PredictionService(ContestRepository contests, ContestParticipantRepository participants,
                             DashboardQueryRepository dashboard, Clock clock) {
        this.contests = contests;
        this.participants = participants;
        this.dashboard = dashboard;
        this.clock = clock;
    }

    // ---------- make or change a pick ----------

    /**
     * Sets the caller's pick. Allowed only while the contest is SCHEDULED, before its start time, and only for a
     * CONFIRMED participant. Calling it again before the start simply replaces the earlier pick.
     */
    @Transactional
    public MyPredictionResponse predict(AuthenticatedUser actor, Long contestId, Long teamId) {
        Contest contest = contests.findByIdWithDetails(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        ContestParticipant row = myRow(actor, contestId);

        if (row.getParticipantStatus() != ParticipantStatus.CONFIRMED) {
            throw new ConflictException("Only confirmed participants can predict. Your status is "
                    + row.getParticipantStatus());
        }
        if (contest.getContestStatus() != ContestStatus.SCHEDULED) {
            throw new ConflictException("This contest is " + contest.getContestStatus() + "; predictions are closed");
        }
        Instant now = clock.instant();
        if (!now.isBefore(contest.getStartTime())) {
            throw new ConflictException("The contest has started; predictions are locked");
        }

        Team picked = pickedTeam(contest, teamId);
        row.setSelectedTeam(picked);
        row.setPredictedAt(now);
        participants.save(row);
        return myResponse(contest, row, now);
    }

    // ---------- read ----------

    /** The caller's own pick. Visible to them at any time, including after the contest has started. */
    @Transactional(readOnly = true)
    public MyPredictionResponse myPrediction(AuthenticatedUser actor, Long contestId) {
        Contest contest = contests.findByIdWithDetails(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        ContestParticipant row = myRow(actor, contestId);
        return myResponse(contest, row, clock.instant());
    }

    /**
     * Everyone's picks, including confirmed participants who never predicted. Refused until the contest starts:
     * before then, a pick is private.
     */
    @Transactional(readOnly = true)
    public List<PredictionRowResponse> allPredictions(AuthenticatedUser actor, Long contestId) {
        Contest contest = contests.findByIdWithDetails(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        requireParticipantOrOrganizer(actor, contest, contestId);

        if (clock.instant().isBefore(contest.getStartTime())) {
            throw new ConflictException("Predictions are hidden until the contest starts");
        }

        Map<Long, String> codes = new HashMap<>();
        codes.put(contest.getTeamA().getId(), contest.getTeamA().getShortCode());
        codes.put(contest.getTeamB().getId(), contest.getTeamB().getShortCode());

        List<VisiblePredictionRow> picked = dashboard.visiblePredictions(contestId);
        Set<Long> withPick = new HashSet<>();
        List<PredictionRowResponse> out = new ArrayList<>(picked.size());
        for (VisiblePredictionRow p : picked) {
            withPick.add(p.userId());
            out.add(new PredictionRowResponse(p.userId(), p.displayName(), p.selectedTeamId(),
                    codes.get(p.selectedTeamId()), p.userId() == actor.id()));
        }

        // Confirmed participants who never picked still belong on the list: their entry counts toward the pool.
        for (ContestParticipant row : participants.findByStatusWithUser(
                contestId, ParticipantStatus.CONFIRMED)) {
            Long userId = row.getUser().getId();
            if (withPick.contains(userId)) {
                continue;
            }
            out.add(new PredictionRowResponse(userId, row.getUser().getDisplayName(), null, null,
                    userId.equals(actor.id())));
        }
        out.sort((a, b) -> {
            int byPick = Boolean.compare(a.selectedTeamId() == null, b.selectedTeamId() == null);
            return byPick != 0 ? byPick : String.valueOf(a.displayName()).compareToIgnoreCase(String.valueOf(b.displayName()));
        });
        return List.copyOf(out);
    }

    // ---------- helpers ----------

    private ContestParticipant myRow(AuthenticatedUser actor, Long contestId) {
        return participants.findByContest_IdAndUser_Id(contestId, actor.id())
                .orElseThrow(() -> new ForbiddenException("You are not a participant in this contest"));
    }

    /** The organizer of a standalone contest may not be a participant in it, but may still see the picks. */
    private void requireParticipantOrOrganizer(AuthenticatedUser actor, Contest contest, Long contestId) {
        if (participants.findByContest_IdAndUser_Id(contestId, actor.id()).isPresent()) {
            return;
        }
        if (contest.getOrganizer().getId().equals(actor.id()) || actor.isAdmin()) {
            return;
        }
        throw new ForbiddenException("You are not a participant in this contest");
    }

    private static Team pickedTeam(Contest contest, Long teamId) {
        if (teamId == null) {
            throw new BadRequestException("Pick a team");
        }
        if (teamId.equals(contest.getTeamA().getId())) {
            return contest.getTeamA();
        }
        if (teamId.equals(contest.getTeamB().getId())) {
            return contest.getTeamB();
        }
        throw new BadRequestException("That team is not playing in this contest");
    }

    private static MyPredictionResponse myResponse(Contest contest, ContestParticipant row, Instant now) {
        Team picked = row.getSelectedTeam();
        boolean locked = !now.isBefore(contest.getStartTime())
                || contest.getContestStatus() != ContestStatus.SCHEDULED;
        return new MyPredictionResponse(
                contest.getId(),
                picked == null ? null : picked.getId(),
                picked == null ? null : picked.getName(),
                picked == null ? null : picked.getShortCode(),
                row.getPredictedAt(),
                contest.getStartTime(),
                locked,
                TeamRef.from(contest.getTeamA()),
                TeamRef.from(contest.getTeamB()));
    }
}
