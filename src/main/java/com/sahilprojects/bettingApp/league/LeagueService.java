package com.sahilprojects.bettingApp.league;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.ForbiddenException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.contest.Contest;
import com.sahilprojects.bettingApp.contest.ContestRepository;
import com.sahilprojects.bettingApp.contest.ContestStatus;
import com.sahilprojects.bettingApp.league.dto.CreateLeagueRequest;
import com.sahilprojects.bettingApp.league.dto.LeagueResponse;
import com.sahilprojects.bettingApp.league.dto.LeagueSummaryResponse;
import com.sahilprojects.bettingApp.league.dto.UpdateLeagueRequest;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.settlement.SettlementService;
import com.sahilprojects.bettingApp.tournament.Tournament;
import com.sahilprojects.bettingApp.tournament.TournamentRepository;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Creating, viewing, editing, opening and cancelling a league.
 * The member workflow is in LeagueMembershipService, and ending and settling a league in SettlementService.
 */
@Service
public class LeagueService {

    private static final List<LeagueMemberStatus> BLOCKS_MONEY_EDITS =
            List.of(LeagueMemberStatus.ACCEPTED_UNPAID, LeagueMemberStatus.CONFIRMED);
    private static final List<LeagueMemberStatus> HIDDEN_FROM_MY_LEAGUES =
            List.of(LeagueMemberStatus.REJECTED, LeagueMemberStatus.CANCELLED);

    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final TournamentRepository tournaments;
    private final UserRepository users;
    private final ContestRepository contests;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final SettlementService settlementService;
    private final NotificationService notificationService;
    private final Clock clock;

    public LeagueService(LeagueRepository leagues, LeagueMemberRepository members, TournamentRepository tournaments,
                         UserRepository users, ContestRepository contests, AccessGuard accessGuard,
                         AuditService auditService, SettlementService settlementService,
                         NotificationService notificationService, Clock clock) {
        this.leagues = leagues;
        this.members = members;
        this.tournaments = tournaments;
        this.users = users;
        this.contests = contests;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.settlementService = settlementService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---------- create ----------

    /**
     * Anyone logged in can create a league and becomes its organizer. The organizer is also added as a CONFIRMED,
     * PAID member, so they can play in their own league. They hold the cash, so there is nothing for them to pay.
     */
    @Transactional
    public LeagueResponse create(AuthenticatedUser actor, CreateLeagueRequest request) {
        Instant now = clock.instant();
        Tournament tournament = tournaments.findById(request.tournamentId())
                .filter(Tournament::isActive)
                .orElseThrow(() -> new NotFoundException("Tournament not found or no longer active"));
        validateSchedule(request.registrationStart(), request.registrationDeadline(), request.paymentDeadline(), now, true);

        User organizer = users.getReferenceById(actor.id());
        League league = new League();
        league.setOrganizer(organizer);
        league.setTournament(tournament);
        league.setName(request.name().trim());
        league.setAmountPerContest(request.amountPerContest());
        league.setTotalContests(request.totalContests());
        league.setRegistrationStart(request.registrationStart());
        league.setRegistrationDeadline(request.registrationDeadline());
        league.setPaymentDeadline(request.paymentDeadline());
        league.setPaymentInstructions(blankToNull(request.paymentInstructions()));
        league.setInviteCode(newInviteCode());
        league.setStatus(request.registrationStart().isAfter(now) ? LeagueStatus.UPCOMING : LeagueStatus.OPEN);
        leagues.save(league);

        LeagueMember own = new LeagueMember();
        own.setLeague(league);
        own.setUser(organizer);
        own.setLeagueMemberStatus(LeagueMemberStatus.CONFIRMED);
        own.setPaymentStatus(PaymentStatus.PAID);
        own.setPaidAt(now);
        own.setConfirmedAt(now);
        members.save(own);

        audit(AuditAction.LEAGUE_CREATED, league, actor.id(), ActorType.USER, null,
                null, Map.of("status", league.getStatus().name(), "totalContests", league.getTotalContests()));
        return toResponse(league, actor, Optional.of(own));
    }

    // ---------- read ----------

    /** Full details for the organizer, admins, and anyone with a membership row (including pending requests). */
    @Transactional(readOnly = true)
    public LeagueResponse get(AuthenticatedUser actor, Long leagueId) {
        League league = load(leagueId);
        Optional<LeagueMember> mine = members.findByLeague_IdAndUser_Id(leagueId, actor.id());
        boolean organizer = actor.id().equals(league.getOrganizer().getId());
        if (!organizer && !actor.isAdmin() && mine.isEmpty()) {
            throw new ForbiddenException("You are not part of this league. Use its invitation link to join");
        }
        return toResponse(league, actor, mine);
    }

    /** The join screen: what a person sees when they open an invitation link. Shows the amount they would pay and could lose. */
    @Transactional(readOnly = true)
    public LeagueResponse getByInviteCode(AuthenticatedUser actor, String inviteCode) {
        League league = leagues.findByInviteCode(inviteCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("No league found for this invitation code"));
        return toResponse(league, actor, members.findByLeague_IdAndUser_Id(league.getId(), actor.id()));
    }

    /** The leagues I belong to or have asked to join, newest first. The organizer has a member row, so organizers are included. */
    @Transactional(readOnly = true)
    public List<LeagueSummaryResponse> listMine(AuthenticatedUser actor) {
        List<LeagueMember> mine = members.findByUser_IdAndLeagueMemberStatusIn(actor.id(),
                Arrays.stream(LeagueMemberStatus.values()).filter(s -> !HIDDEN_FROM_MY_LEAGUES.contains(s)).toList());
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, LeagueMember> byLeague = mine.stream()
                .collect(Collectors.toMap(m -> m.getLeague().getId(), m -> m, (a, b) -> a));
        return leagues.findAllWithOrganizerAndTournament(byLeague.keySet()).stream()
                .map(l -> new LeagueSummaryResponse(l.getId(), l.getName(), l.getTournament().getName(),
                        l.getOrganizer().getDisplayName(), l.getAmountPerContest(), l.getTotalContests(),
                        joiningAmount(l), l.getStatus(), byLeague.get(l.getId()).currentStatus(),
                        l.getOrganizer().getId().equals(actor.id()),
                        members.countByLeague_IdAndLeagueMemberStatus(l.getId(), LeagueMemberStatus.CONFIRMED)))
                .toList();
    }

    // ---------- edit ----------

    /**
     * Organizer only, while the league is UPCOMING or OPEN. The amount and number of contests are locked once any
     * member other than the organizer is ACCEPTED_UNPAID or CONFIRMED, or once a contest exists, because members
     * have been told what they will pay and contests copy the amount.
     */
    @Transactional
    public LeagueResponse update(AuthenticatedUser actor, Long leagueId, UpdateLeagueRequest request) {
        League league = loadForUpdate(leagueId);
        accessGuard.requireOrganizer(actor, league);
        if (!request.version().equals(league.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(League.class, leagueId);   // handler answers 409 STALE_DATA
        }
        requireStatus(league, "edited", LeagueStatus.UPCOMING, LeagueStatus.OPEN);
        Instant now = clock.instant();
        // After the registration deadline has passed the organizer must still be able to extend the payment deadline,
        // so a registration deadline that is left unchanged does not have to be in the future.
        boolean registrationDeadlineChanged =
                !truncate(request.registrationDeadline()).equals(truncate(league.getRegistrationDeadline()));
        boolean paymentDeadlineChanged =
                !truncate(request.paymentDeadline()).equals(truncate(league.getPaymentDeadline()));
        validateSchedule(request.registrationStart(), request.registrationDeadline(), request.paymentDeadline(),
                now, registrationDeadlineChanged);
        if (paymentDeadlineChanged && !request.paymentDeadline().isAfter(now)) {
            throw new BadRequestException("The payment deadline must be in the future");
        }
        // Every contest must start after members have had to pay, the same rule contest creation applies.
        if (paymentDeadlineChanged) {
            contests.findByLeague_IdOrderByStartTimeAsc(leagueId).stream()
                    .filter(c -> c.getContestStatus() != ContestStatus.CANCELLED)
                    .findFirst()
                    .filter(first -> !first.getStartTime().isAfter(request.paymentDeadline()))
                    .ifPresent(first -> {
                        throw new BadRequestException("The payment deadline must be before the first contest starts ("
                                + first.getStartTime() + "). Move that contest first");
                    });
        }

        boolean moneyChanged = request.amountPerContest().compareTo(league.getAmountPerContest()) != 0
                || !request.totalContests().equals(league.getTotalContests());
        if (moneyChanged && moneyEditsLocked(league)) {
            throw new ConflictException("The amount and the number of contests cannot change once members have been accepted or contests created");
        }
        boolean startChanged = !truncate(request.registrationStart()).equals(truncate(league.getRegistrationStart()));
        if (startChanged && league.getStatus() == LeagueStatus.OPEN) {
            throw new ConflictException("Registration has already opened, so its start cannot change");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        change("name", league.getName(), request.name().trim(), before, after);
        change("amountPerContest", league.getAmountPerContest(), request.amountPerContest(), before, after);
        change("totalContests", league.getTotalContests(), request.totalContests(), before, after);
        change("registrationStart", league.getRegistrationStart(), request.registrationStart(), before, after);
        change("registrationDeadline", league.getRegistrationDeadline(), request.registrationDeadline(), before, after);
        change("paymentDeadline", league.getPaymentDeadline(), request.paymentDeadline(), before, after);
        change("paymentInstructions", league.getPaymentInstructions(), blankToNull(request.paymentInstructions()), before, after);

        league.setName(request.name().trim());
        league.setAmountPerContest(request.amountPerContest());
        league.setTotalContests(request.totalContests());
        league.setRegistrationStart(request.registrationStart());
        league.setRegistrationDeadline(request.registrationDeadline());
        league.setPaymentDeadline(request.paymentDeadline());
        league.setPaymentInstructions(blankToNull(request.paymentInstructions()));
        if (league.getStatus() == LeagueStatus.UPCOMING && !request.registrationStart().isAfter(now)) {
            league.setStatus(LeagueStatus.OPEN);
        }
        leagues.saveAndFlush(league);                       // flush now so the response carries the new version

        if (!after.isEmpty()) {
            audit(AuditAction.LEAGUE_UPDATED, league, actor.id(), ActorType.USER, null, before, after);
        }
        return toResponse(league, actor, members.findByLeague_IdAndUser_Id(leagueId, actor.id()));
    }

    // ---------- open joining ----------

    /** The organizer opens joining before the scheduled start. */
    @Transactional
    public LeagueResponse openJoining(AuthenticatedUser actor, Long leagueId) {
        League league = loadForUpdate(leagueId);
        accessGuard.requireOrganizer(actor, league);
        if (league.getStatus() == LeagueStatus.OPEN) {
            throw new ConflictException("Joining is already open");
        }
        requireStatus(league, "opened", LeagueStatus.UPCOMING);
        Instant now = clock.instant();
        if (!league.getRegistrationDeadline().isAfter(now)) {
            throw new BadRequestException("The registration deadline has passed. Edit the league to extend it first");
        }
        Instant oldStart = league.getRegistrationStart();
        if (oldStart.isAfter(now)) {
            league.setRegistrationStart(now);
        }
        league.setStatus(LeagueStatus.OPEN);
        leagues.saveAndFlush(league);
        audit(AuditAction.LEAGUE_OPENED, league, actor.id(), ActorType.USER, null,
                Map.of("status", "UPCOMING"), Map.of("status", "OPEN"));
        return toResponse(league, actor, members.findByLeague_IdAndUser_Id(leagueId, actor.id()));
    }

    /**
     * For the scheduled job: opens every UPCOMING league whose registration start has passed.
     * Rows another run has locked are skipped, so running it twice is safe. Returns how many were opened.
     */
    @Transactional
    public int openDueLeagues() {
        List<League> due = leagues.lockToOpen(LeagueStatus.UPCOMING, clock.instant());
        for (League league : due) {
            league.setStatus(LeagueStatus.OPEN);
            audit(AuditAction.LEAGUE_OPENED, league, null, ActorType.SYSTEM, "Registration start reached",
                    Map.of("status", "UPCOMING"), Map.of("status", "OPEN"));
        }
        return due.size();
    }

    // ---------- cancel ----------

    /**
     * Cancels a league that has not started. The organizer, or an admin with a written reason.
     *
     * Where members had already paid their joining amount, the league cannot simply vanish: a settlement row is
     * written for each paid member so the organizer has a list of money to hand back, and those rows are ticked off
     * while the league sits in CANCELLED. No contest has been played, so each balance is exactly what was paid in.
     * Contests that have not started are cancelled with the league, and requests still waiting are withdrawn.
     */
    @Transactional
    public LeagueResponse cancel(AuthenticatedUser actor, Long leagueId, String reason) {
        League league = loadForUpdate(leagueId);
        ActorType actingAs = accessGuard.requireOrganizerOrAdmin(actor, league, reason);
        requireStatus(league, "cancelled here", LeagueStatus.UPCOMING, LeagueStatus.OPEN);

        Instant now = clock.instant();
        for (Contest contest : contests.findByLeague_IdAndContestStatus(leagueId, ContestStatus.SCHEDULED)) {
            contest.setContestStatus(ContestStatus.CANCELLED);
            contest.setCancelledReason("League cancelled");
        }

        int requestsDropped = 0;
        for (LeagueMember member : members.findByLeague_IdAndLeagueMemberStatus(
                leagueId, LeagueMemberStatus.PENDING_APPROVAL)) {
            member.setLeagueMemberStatus(LeagueMemberStatus.CANCELLED);
            member.setStatusReason("The league was cancelled");
            requestsDropped++;
        }

        int refundRows = 0;
        if (members.existsByLeague_IdAndPaymentStatusAndUser_IdNot(leagueId, PaymentStatus.PAID,
                league.getOrganizer().getId())) {
            refundRows = settlementService.writeRows(league, now).size();
        }

        LeagueStatus before = league.getStatus();
        league.setStatus(LeagueStatus.CANCELLED);
        league.setEndedAt(now);
        leagues.saveAndFlush(league);

        String message = refundRows > 0
                ? "Your joining amount will be returned by the organizer."
                : (reason == null || reason.isBlank() ? "The organizer cancelled the league." : reason.trim());
        for (LeagueMember member : members.findByLeague_IdAndLeagueMemberStatus(
                leagueId, LeagueMemberStatus.CONFIRMED)) {
            notificationService.notifyOnce(member.getUser().getId(), NotificationType.LEAGUE_CANCELLED,
                    ReferenceType.LEAGUE, leagueId,
                    league.getName() + " was cancelled", message, "league-cancelled");
        }

        audit(AuditAction.LEAGUE_CANCELLED, league, actor.id(), actingAs, blankToNull(reason),
                Map.of("status", before.name()),
                Map.of("status", "CANCELLED", "cancelledAt", now.toString(),
                        "settlementRows", refundRows, "requestsDropped", requestsDropped));
        return toResponse(league, actor, members.findByLeague_IdAndUser_Id(leagueId, actor.id()));
    }

    // ---------- helpers ----------

    private League load(Long id) {
        return leagues.findById(id).orElseThrow(() -> new NotFoundException("League not found"));
    }

    /** Locks the league row so edits, joins and contest creation do not interleave. */
    private League loadForUpdate(Long id) {
        return leagues.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("League not found"));
    }

    private static void requireStatus(League league, String action, LeagueStatus... allowed) {
        if (!Arrays.asList(allowed).contains(league.getStatus())) {
            throw new ConflictException("This league cannot be " + action + " because it is " + league.getStatus());
        }
    }

    private boolean moneyEditsLocked(League league) {
        boolean others = members.existsByLeague_IdAndLeagueMemberStatusInAndUser_IdNot(
                league.getId(), BLOCKS_MONEY_EDITS, league.getOrganizer().getId());
        boolean hasContests = contests.countByLeague_IdAndContestStatusNot(league.getId(), ContestStatus.CANCELLED) > 0;
        return others || hasContests;
    }

    private static void validateSchedule(Instant registrationStart, Instant registrationDeadline,
                                         Instant paymentDeadline, Instant now, boolean registrationDeadlineMustBeFuture) {
        if (registrationDeadline.isBefore(registrationStart)) {
            throw new BadRequestException("The registration deadline cannot be before registration opens");
        }
        if (paymentDeadline.isBefore(registrationDeadline)) {
            throw new BadRequestException("The payment deadline cannot be before the registration deadline");
        }
        if (registrationDeadlineMustBeFuture && !registrationDeadline.isAfter(now)) {
            throw new BadRequestException("The registration deadline must be in the future");
        }
    }

    private String newInviteCode() {
        for (int i = 0; i < 10; i++) {
            String code = InviteCodes.next();
            if (!leagues.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique invitation code");
    }

    /** Same as the database's generated column (amount per contest times total), without needing to reload the row. */
    private static BigDecimal joiningAmount(League league) {
        return league.getAmountPerContest().multiply(BigDecimal.valueOf(league.getTotalContests()));
    }

    private LeagueResponse toResponse(League league, AuthenticatedUser actor, Optional<LeagueMember> mine) {
        boolean organizer = actor.id().equals(league.getOrganizer().getId());
        boolean privileged = organizer || actor.isAdmin();
        boolean accepted = mine.map(m -> m.getLeagueMemberStatus() == LeagueMemberStatus.ACCEPTED_UNPAID
                || m.getLeagueMemberStatus() == LeagueMemberStatus.CONFIRMED).orElse(false);
        return new LeagueResponse(
                league.getId(), league.getName(),
                league.getTournament().getId(), league.getTournament().getName(),
                league.getOrganizer().getId(), league.getOrganizer().getDisplayName(),
                league.getAmountPerContest(), league.getTotalContests(), joiningAmount(league),
                league.getRegistrationStart(), league.getRegistrationDeadline(), league.getPaymentDeadline(),
                privileged || accepted ? league.getPaymentInstructions() : null,
                privileged ? league.getInviteCode() : null,
                league.getStatus(), league.getStartedAt(), league.getEndedAt(), league.getCompletedAt(),
                members.countByLeague_IdAndLeagueMemberStatus(league.getId(), LeagueMemberStatus.CONFIRMED),
                mine.map(LeagueMember::currentStatus).orElse(null),
                organizer, league.getVersion(),
                contests.countByLeague_IdAndContestStatusNot(league.getId(), ContestStatus.CANCELLED),
                mine.map(m -> m.getPaymentStatus().name()).orElse(null));
    }

    private void audit(AuditAction action, League league, Long actorId, ActorType actorType, String reason,
                       Map<String, Object> before, Map<String, Object> after) {
        auditService.record(AuditEntry.builder()
                .action(action).entityType("LEAGUE").entityId(league.getId()).leagueId(league.getId())
                .actorId(actorId).actorType(actorType).reason(reason)
                .before(before).after(after).build());
    }

    private static void change(String field, Object oldValue, Object newValue,
                               Map<String, Object> before, Map<String, Object> after) {
        boolean same;
        if (oldValue instanceof BigDecimal o && newValue instanceof BigDecimal n) {
            same = o.compareTo(n) == 0;
        } else if (oldValue instanceof Instant o && newValue instanceof Instant n) {
            same = truncate(o).equals(truncate(n));            // the database keeps microseconds, requests usually carry less
        } else {
            same = Objects.equals(oldValue, newValue);
        }
        if (!same) {
            before.put(field, oldValue == null ? "" : oldValue.toString());
            after.put(field, newValue == null ? "" : newValue.toString());
        }
    }

    private static Instant truncate(Instant value) {
        return value.truncatedTo(ChronoUnit.MILLIS);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
