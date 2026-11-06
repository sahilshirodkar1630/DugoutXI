package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.contest.dto.*;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.league.LeagueRepository;
import com.sahilprojects.bettingApp.league.LeagueStatus;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.payout.PayoutService;
import com.sahilprojects.bettingApp.tournament.Team;
import com.sahilprojects.bettingApp.tournament.TeamRepository;
import com.sahilprojects.bettingApp.tournament.Tournament;
import com.sahilprojects.bettingApp.tournament.TournamentRepository;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Creating, viewing, rescheduling and cancelling contests, for leagues and for standalone contests.
 *
 * Picking a winner lives in PredictionService, the result in ResultService, the money in PayoutService, and
 * requesting a place in somebody's standalone contest in ContestMembershipService.
 */
@Service
public class ContestService {

    private static final List<LeagueStatus> ACCEPTS_NEW_CONTESTS =
            List.of(LeagueStatus.UPCOMING, LeagueStatus.OPEN, LeagueStatus.ACTIVE);
    private static final List<ParticipantStatus> HIDDEN_FROM_MY_CONTESTS =
            List.of(ParticipantStatus.REJECTED, ParticipantStatus.CANCELLED);

    private final ContestRepository contests;
    private final ContestParticipantRepository participants;
    private final LeagueRepository leagues;
    private final TournamentRepository tournaments;
    private final TeamRepository teams;
    private final UserRepository users;
    private final EnrolmentService enrolment;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final PayoutService payoutService;
    private final NotificationService notificationService;
    private final Clock clock;

    public ContestService(ContestRepository contests, ContestParticipantRepository participants, LeagueRepository leagues,
                          TournamentRepository tournaments, TeamRepository teams, UserRepository users,
                          EnrolmentService enrolment, AccessGuard accessGuard, AuditService auditService,
                          PayoutService payoutService, NotificationService notificationService, Clock clock) {
        this.contests = contests;
        this.participants = participants;
        this.leagues = leagues;
        this.tournaments = tournaments;
        this.teams = teams;
        this.users = users;
        this.enrolment = enrolment;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.payoutService = payoutService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---------- create ----------

    @Transactional
    public ContestResponse create(AuthenticatedUser actor, CreateContestRequest request) {
        Contest contest = request.leagueId() != null
                ? buildLeagueContest(actor, request)
                : buildStandaloneContest(actor, request);
        contests.saveAndFlush(contest);

        if (contest.isStandalone()) {
            // the organizer may play in their own standalone contest. They hold the cash, so their entry counts as paid
            ContestParticipant own = new ContestParticipant();
            own.setContest(contest);
            own.setUser(contest.getOrganizer());
            own.setParticipantStatus(ParticipantStatus.CONFIRMED);
            own.setPaymentStatus(ContestPaymentStatus.PAID);
            own.setConfirmedAt(clock.instant());
            participants.save(own);
        } else {
            enrolment.enrolLeagueMembers(contest);
        }
        scheduleReminders(contest);

        audit(AuditAction.CONTEST_CREATED, contest, actor.id(), ActorType.USER, null, null,
                Map.of("startTime", contest.getStartTime().toString(), "teamA", contest.getTeamA().getShortCode(),
                        "teamB", contest.getTeamB().getShortCode()));
        return toResponse(contest, actor);
    }

    private Contest buildLeagueContest(AuthenticatedUser actor, CreateContestRequest request) {
        if (request.tournamentId() != null || request.amount() != null || request.registrationDeadline() != null
                || request.paymentDeadline() != null || request.paymentInstructions() != null) {
            throw new BadRequestException("A league contest takes its tournament, amount and deadlines from the league. "
                    + "Leave tournamentId, amount, registrationDeadline, paymentDeadline and paymentInstructions out");
        }
        League league = leagues.findByIdForUpdate(request.leagueId())
                .orElseThrow(() -> new NotFoundException("League not found"));
        accessGuard.requireOrganizer(actor, league);
        if (!ACCEPTS_NEW_CONTESTS.contains(league.getStatus())) {
            throw new ConflictException("No new contests can be added because the league is " + league.getStatus());
        }

        Instant now = clock.instant();
        if (!request.startTime().isAfter(now)) {
            throw new BadRequestException("The start time must be in the future");
        }
        if (!request.startTime().isAfter(league.getPaymentDeadline())) {
            throw new BadRequestException("The start time must be after the league's payment deadline");
        }
        long planned = contests.countByLeague_IdAndContestStatusNot(league.getId(), ContestStatus.CANCELLED);
        if (planned >= league.getTotalContests()) {
            throw new ConflictException("All " + league.getTotalContests() + " planned contests have been created. "
                    + "Cancel one first, or the league's total cannot be raised once contests exist");
        }

        Tournament tournament = league.getTournament();
        Team teamA = playingTeam(request.teamAId(), tournament);
        Team teamB = playingTeam(request.teamBId(), tournament);
        requireDifferent(teamA, teamB);

        Contest contest = new Contest();
        contest.setLeague(league);
        contest.setOrganizer(league.getOrganizer());
        contest.setTournament(tournament);
        contest.setName(blankToNull(request.name()));
        contest.setContestNumber(chooseNumber(league, request.contestNumber()));
        contest.setTeamA(teamA);
        contest.setTeamB(teamB);
        contest.setStartTime(request.startTime());
        contest.setContestAmount(league.getAmountPerContest());
        return contest;
    }

    private Contest buildStandaloneContest(AuthenticatedUser actor, CreateContestRequest request) {
        if (request.tournamentId() == null || request.amount() == null
                || request.registrationDeadline() == null || request.paymentDeadline() == null) {
            throw new BadRequestException("A standalone contest needs tournamentId, amount, registrationDeadline and paymentDeadline");
        }
        if (request.contestNumber() != null) {
            throw new BadRequestException("Only league contests have a contest number");
        }
        Tournament tournament = tournaments.findById(request.tournamentId())
                .filter(Tournament::isActive)
                .orElseThrow(() -> new NotFoundException("Tournament not found or no longer active"));

        Instant now = clock.instant();
        if (!request.startTime().isAfter(now)) {
            throw new BadRequestException("The start time must be in the future");
        }
        if (!request.registrationDeadline().isAfter(now)) {
            throw new BadRequestException("The registration deadline must be in the future");
        }
        if (request.paymentDeadline().isBefore(request.registrationDeadline())) {
            throw new BadRequestException("The payment deadline cannot be before the registration deadline");
        }
        if (request.paymentDeadline().isAfter(request.startTime())) {
            throw new BadRequestException("The payment deadline cannot be after the start time");
        }

        Team teamA = playingTeam(request.teamAId(), tournament);
        Team teamB = playingTeam(request.teamBId(), tournament);
        requireDifferent(teamA, teamB);

        Contest contest = new Contest();
        contest.setOrganizer(users.getReferenceById(actor.id()));
        contest.setTournament(tournament);
        contest.setName(blankToNull(request.name()));
        contest.setTeamA(teamA);
        contest.setTeamB(teamB);
        contest.setStartTime(request.startTime());
        contest.setContestAmount(request.amount());
        contest.setRegistrationDeadline(request.registrationDeadline());
        contest.setPaymentDeadline(request.paymentDeadline());
        contest.setPaymentInstructions(blankToNull(request.paymentInstructions()));
        return contest;
    }

    // ---------- read ----------

    /** League contests: organizer, admins and confirmed members. Standalone contests: any logged-in user, so the link can be shared. */
    @Transactional(readOnly = true)
    public ContestResponse get(AuthenticatedUser actor, Long contestId) {
        Contest contest = contests.findByIdWithDetails(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        if (contest.getLeague() != null) {
            accessGuard.requireLeagueMember(actor, contest.getLeague());
        }
        return toResponse(contest, actor);
    }

    @Transactional(readOnly = true)
    public List<ContestSummaryResponse> listForLeague(AuthenticatedUser actor, Long leagueId) {
        League league = leagues.findById(leagueId).orElseThrow(() -> new NotFoundException("League not found"));
        accessGuard.requireLeagueMember(actor, league);
        return contests.findAllByLeagueWithTeams(leagueId).stream().map(c -> toSummary(c, null)).toList();
    }

    /** Standalone contests I organize or take part in, newest first. */
    @Transactional(readOnly = true)
    public List<ContestSummaryResponse> listMyStandalone(AuthenticatedUser actor) {
        return participants.findStandaloneForUser(actor.id(), HIDDEN_FROM_MY_CONTESTS).stream()
                .map(cp -> toSummary(cp.getContest(), cp.getParticipantStatus().name()))
                .toList();
    }

    // ---------- change start time ----------

    /**
     * Organizer, or an admin with a reason. Only before the current start time has passed.
     * The new time must be in the future and after the payment deadline. Every change is audited and visible to members.
     */
    @Transactional
    public ContestResponse changeStartTime(AuthenticatedUser actor, Long contestId, ChangeStartTimeRequest request) {
        Contest contest = contests.findByIdForUpdate(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, contest, request.reason());
        Instant now = clock.instant();
        if (contest.getContestStatus() != ContestStatus.SCHEDULED || !now.isBefore(contest.getStartTime())) {
            throw new ConflictException("The start time can only be changed before the contest has started");
        }
        Instant newStart = request.startTime();
        if (!newStart.isAfter(now)) {
            throw new BadRequestException("The new start time must be in the future");
        }
        if (contest.isStandalone()) {
            if (newStart.isBefore(contest.getPaymentDeadline())) {
                throw new BadRequestException("The new start time cannot be before the payment deadline");
            }
        } else if (!newStart.isAfter(contest.getLeague().getPaymentDeadline())) {
            throw new BadRequestException("The new start time must be after the league's payment deadline");
        }
        if (newStart.equals(contest.getStartTime())) {
            return toResponse(contest, actor);                           // nothing to change, nothing to audit
        }

        Instant old = contest.getStartTime();
        contest.setStartTime(newStart);
        contests.saveAndFlush(contest);

        // The queued reminders were timed off the old start, so they are thrown away and timed again.
        scheduleReminders(contest);
        for (Long userId : interestedUserIds(contest)) {
            notificationService.notifyNow(userId, NotificationType.CONTEST_START_TIME_CHANGED,
                    ReferenceType.CONTEST, contestId,
                    label(contest) + " was rescheduled",
                    "It now starts at " + newStart + "."
                            + (request.reason() == null || request.reason().isBlank() ? "" : " " + request.reason().trim()));
        }

        audit(AuditAction.START_TIME_CHANGED, contest, actor.id(), actingAs, blankToNull(request.reason()),
                Map.of("startTime", old.toString()), Map.of("startTime", newStart.toString()));
        return toResponse(contest, actor);
    }

    // ---------- reminders ----------

    /** Queues the hour-before and quarter-hour-before nudges for everyone who has a place in the contest. */
    private void scheduleReminders(Contest contest) {
        notificationService.rebuildContestReminders(contest.getId(), label(contest), contest.getStartTime(),
                interestedUserIds(contest));
    }

    /** Everyone with a live place in the contest: confirmed players, plus anyone still waiting to be accepted. */
    private List<Long> interestedUserIds(Contest contest) {
        List<Long> ids = new ArrayList<>();
        for (ContestParticipant row : participants.findByContestWithUser(contest.getId())) {
            switch (row.getParticipantStatus()) {
                case CONFIRMED, ACCEPTED_UNPAID, PENDING_APPROVAL -> ids.add(row.getUser().getId());
                default -> {
                    // rejected, removed or withdrawn: no longer their contest
                }
            }
        }
        return ids;
    }

    private static String label(Contest contest) {
        if (contest.getName() != null && !contest.getName().isBlank()) {
            return contest.getName();
        }
        return contest.getTeamA().getShortCode() + " vs " + contest.getTeamB().getShortCode();
    }

    // ---------- cancel ----------

    /**
     * Organizer, or an admin with a reason, before the contest starts. A cancelled league contest no longer uses up
     * one of the league's planned contests, so a replacement can be created.
     *
     * In a standalone contest, everyone who had already paid gets a REFUND payout, which the organizer then marks
     * as handed over. In a league contest nothing is refunded here: the joining amount covers the whole league and
     * one contest fewer simply means one fewer entry spent.
     */
    @Transactional
    public ContestResponse cancel(AuthenticatedUser actor, Long contestId, String reason) {
        Contest contest = contests.findByIdForUpdate(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, contest, reason);
        if (contest.getContestStatus() != ContestStatus.SCHEDULED) {
            throw new ConflictException("Only a contest that has not started can be cancelled (it is " + contest.getContestStatus() + ")");
        }

        contest.setContestStatus(ContestStatus.CANCELLED);
        contest.setCancelledReason(reason == null || reason.isBlank() ? "Cancelled by the organizer" : reason.trim());

        int refunds = 0;
        if (contest.isStandalone()) {
            List<ContestParticipant> paid = participants.findPaidWithUser(contestId, ContestPaymentStatus.PAID);
            refunds = payoutService.createRefundsForCancelledContest(contest, paid).size();
        }
        contests.saveAndFlush(contest);
        notificationService.cancelContestReminders(contestId);
        tellParticipantsTheContestIsOff(contest, refunds);

        audit(AuditAction.CONTEST_CANCELLED, contest, actor.id(), actingAs, blankToNull(reason),
                Map.of("status", "SCHEDULED"), Map.of("status", "CANCELLED", "refundsCreated", refunds));
        return toResponse(contest, actor);
    }

    /** Everyone who had a place hears about it, with a word on their money where there is any. */
    private void tellParticipantsTheContestIsOff(Contest contest, int refunds) {
        String label = contest.getName() != null && !contest.getName().isBlank()
                ? contest.getName()
                : contest.getTeamA().getShortCode() + " vs " + contest.getTeamB().getShortCode();
        String money = refunds > 0
                ? " Your entry will be refunded by the organizer."
                : (contest.isStandalone() ? "" : " Your league joining amount is unaffected.");
        for (ContestParticipant row : participants.findByContestWithUser(contest.getId())) {
            if (row.getParticipantStatus() == ParticipantStatus.REJECTED
                    || row.getParticipantStatus() == ParticipantStatus.CANCELLED) {
                continue;
            }
            notificationService.notifyOnce(row.getUser().getId(), NotificationType.CONTEST_CANCELLED,
                    ReferenceType.CONTEST, contest.getId(),
                    label + " was cancelled",
                    (contest.getCancelledReason() == null ? "" : contest.getCancelledReason()) + money,
                    "cancelled");
        }
    }

    // ---------- helpers ----------

    private Team playingTeam(Long teamId, Tournament tournament) {
        Team team = teams.findById(teamId).orElseThrow(() -> new NotFoundException("Team " + teamId + " not found"));
        if (!team.isActive()) {
            throw new BadRequestException(team.getName() + " is not active");
        }
        if (!teams.isInTournament(tournament.getId(), teamId)) {
            throw new BadRequestException(team.getName() + " is not playing in " + tournament.getName());
        }
        return team;
    }

    private static void requireDifferent(Team a, Team b) {
        if (a.getId().equals(b.getId())) {
            throw new BadRequestException("A contest needs two different teams");
        }
    }

    /** A number the organizer asked for, or the lowest free one. Cancelled contests give their number back. */
    private int chooseNumber(League league, Integer requested) {
        Set<Integer> used = new HashSet<>(contests.findNumbersInUse(league.getId(), ContestStatus.CANCELLED));
        if (requested != null) {
            if (requested > league.getTotalContests()) {
                throw new BadRequestException("The contest number cannot be higher than the league's " + league.getTotalContests() + " contests");
            }
            if (used.contains(requested)) {
                throw new ConflictException("Contest number " + requested + " is already used in this league");
            }
            return requested;
        }
        for (int n = 1; n <= league.getTotalContests(); n++) {
            if (!used.contains(n)) {
                return n;
            }
        }
        throw new ConflictException("Every contest number in this league is already used");
    }

    private ContestResponse toResponse(Contest c, AuthenticatedUser actor) {
        boolean organizer = actor.id().equals(c.getOrganizer().getId());
        Optional<ContestParticipant> mine = participants.findByContest_IdAndUser_Id(c.getId(), actor.id());
        boolean accepted = mine.map(p -> p.getParticipantStatus() == ParticipantStatus.ACCEPTED_UNPAID
                || p.getParticipantStatus() == ParticipantStatus.CONFIRMED).orElse(false);
        boolean declared = c.getContestStatus() == ContestStatus.RESULT_DECLARED || c.getContestStatus() == ContestStatus.SETTLED;
        return new ContestResponse(
                c.getId(),
                c.getLeague() == null ? null : c.getLeague().getId(),
                c.getLeague() == null ? null : c.getLeague().getName(),
                c.isStandalone(),
                c.getTournament().getId(), c.getTournament().getName(),
                c.getName(), c.getContestNumber(),
                TeamRef.from(c.getTeamA()), TeamRef.from(c.getTeamB()),
                c.getStartTime(), c.getContestAmount(),
                c.getRegistrationDeadline(), c.getPaymentDeadline(),
                organizer || actor.isAdmin() || accepted ? c.getPaymentInstructions() : null,
                c.getContestStatus(), c.getResultType(),
                declared && c.getWinningTeam() != null ? c.getWinningTeam().getId() : null,
                participants.countByContest_IdAndParticipantStatus(c.getId(), ParticipantStatus.CONFIRMED),
                mine.map(p -> p.getParticipantStatus().name()).orElse(null),
                organizer, c.getOrganizer().getDisplayName(), c.getVersion(),
                declared && c.getWinningTeam() != null ? TeamRef.from(c.getWinningTeam()) : null,
                c.getLeftoverAmount(), c.getLockedAt(), c.getCancelledReason());
    }

    private ContestSummaryResponse toSummary(Contest c, String myStatus) {
        boolean declared = c.getContestStatus() == ContestStatus.RESULT_DECLARED || c.getContestStatus() == ContestStatus.SETTLED;
        boolean hasWinner = declared && c.getWinningTeam() != null;
        return new ContestSummaryResponse(
                c.getId(), c.getLeague() == null ? null : c.getLeague().getId(),
                c.getName(), c.getContestNumber(),
                TeamRef.from(c.getTeamA()), TeamRef.from(c.getTeamB()),
                c.getStartTime(), c.getContestAmount(), c.getContestStatus(), c.getResultType(),
                hasWinner ? c.getWinningTeam().getId() : null,
                myStatus,
                hasWinner ? TeamRef.from(c.getWinningTeam()) : null,
                participants.countByContest_IdAndParticipantStatus(c.getId(), ParticipantStatus.CONFIRMED));
    }

    private void audit(AuditAction action, Contest contest, Long actorId, ActorType actorType, String reason,
                       Map<String, Object> before, Map<String, Object> after) {
        auditService.record(AuditEntry.builder()
                .action(action).entityType("CONTEST").entityId(contest.getId())
                .leagueId(contest.getLeague() == null ? null : contest.getLeague().getId())
                .contestId(contest.getId())
                .actorId(actorId).actorType(actorType).reason(reason)
                .before(before).after(after).build());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
