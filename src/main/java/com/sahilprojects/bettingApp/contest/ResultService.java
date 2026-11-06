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
import com.sahilprojects.bettingApp.contest.dto.DeclareResultRequest;
import com.sahilprojects.bettingApp.contest.dto.ParticipantOutcomeResponse;
import com.sahilprojects.bettingApp.contest.dto.ResultPreviewResponse;
import com.sahilprojects.bettingApp.league.LeagueStatus;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.notification.ReferenceType;
import com.sahilprojects.bettingApp.payout.PayoutCalculator;
import com.sahilprojects.bettingApp.payout.PayoutRepository;
import com.sahilprojects.bettingApp.payout.PayoutService;
import com.sahilprojects.bettingApp.settlement.SettlementRowRepository;
import com.sahilprojects.bettingApp.settlement.SettlementStatus;
import com.sahilprojects.bettingApp.tournament.Team;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declaring who won, and fixing it when it was entered wrong.
 *
 * Declaring is deliberately two steps. The organizer asks for a preview, sees the pool, the winners and the exact
 * amount each one gets, and then confirms. The preview hands back a token derived from the result and from every
 * pick that went into the sums; confirm will only accept that token, so if anything moved in between the organizer
 * is sent back to look again rather than settling figures they never saw.
 *
 * Confirming is one transaction. Every participant's result and money, the contest's own status, the payouts for a
 * standalone contest, the leftover and the audit entry are written together or not at all.
 *
 * A correction needs a reason and is refused once the money it would change has moved: in a standalone contest when
 * someone else's payout has been paid out, and in a league once a settlement row that would change has been settled.
 */
@Service
public class ResultService {

    private final ContestRepository contests;
    private final ContestParticipantRepository participants;
    private final PayoutRepository payouts;
    private final PayoutService payoutService;
    private final SettlementRowRepository settlementRows;
    private final com.sahilprojects.bettingApp.settlement.SettlementService settlementService;
    private final UserRepository users;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    public ResultService(ContestRepository contests, ContestParticipantRepository participants,
                         PayoutRepository payouts, PayoutService payoutService,
                         SettlementRowRepository settlementRows,
                         com.sahilprojects.bettingApp.settlement.SettlementService settlementService,
                         UserRepository users, AccessGuard accessGuard, AuditService auditService,
                         NotificationService notificationService, Clock clock) {
        this.contests = contests;
        this.participants = participants;
        this.payouts = payouts;
        this.payoutService = payoutService;
        this.settlementRows = settlementRows;
        this.settlementService = settlementService;
        this.users = users;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---------- preview ----------

    /** Works out the result without saving anything. Allowed from the moment the contest locks. */
    @Transactional(readOnly = true)
    public ResultPreviewResponse preview(AuthenticatedUser actor, Long contestId, DeclareResultRequest request) {
        Contest contest = loadForDetails(contestId);
        accessGuard.requireOrganizerOrAdmin(actor, contest, request.reason());
        requireReadyForResult(contest);

        Long winningTeamId = resolveWinningTeam(contest, request);
        List<ContestParticipant> confirmed = confirmedParticipants(contestId);
        PayoutCalculator.Settlement settlement =
                PayoutCalculator.compute(contest.getContestAmount(), winningTeamId, toPicks(confirmed));
        return toPreview(contest, winningTeamId, request.noResult(), confirmed, settlement);
    }

    // ---------- confirm ----------

    /**
     * Saves the result. The preview token must match what a fresh calculation produces right now, so the organizer
     * can only confirm the figures they were actually shown.
     */
    @Transactional
    public ResultPreviewResponse confirm(AuthenticatedUser actor, Long contestId, DeclareResultRequest request) {
        Contest contest = contests.findByIdForUpdate(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        ActorType actorType = accessGuard.requireOrganizerOrAdmin(actor, contest, request.reason());
        requireReadyForResult(contest);
        if (contest.getResultType() != null) {
            throw new ConflictException("This contest already has a result. Use the correct endpoint to change it");
        }

        Long winningTeamId = resolveWinningTeam(contest, request);
        List<ContestParticipant> confirmed = confirmedParticipants(contestId);
        PayoutCalculator.Settlement settlement =
                PayoutCalculator.compute(contest.getContestAmount(), winningTeamId, toPicks(confirmed));
        requireMatchingToken(request.previewToken(), contest, winningTeamId, confirmed);

        apply(contest, winningTeamId, request.noResult(), confirmed, settlement, actor);

        auditService.record(AuditEntry.builder()
                .action(AuditAction.RESULT_CONFIRMED)
                .entityType("contest").entityId(contestId)
                .leagueId(contest.getLeague() == null ? null : contest.getLeague().getId())
                .contestId(contestId)
                .actorId(actor.id()).actorType(actorType)
                .reason(request.reason())
                .after(resultSnapshot(contest, settlement))
                .build());

        announce(contest, settlement, confirmed, NotificationType.RESULT_DECLARED, "The result is in");
        return toPreview(contest, winningTeamId, request.noResult(), confirmed, settlement);
    }

    // ---------- correct ----------

    /**
     * Replaces an already-declared result. Needs a reason, and refuses to touch money that has already moved.
     * Once a league has ended only an admin may correct a contest in it, and only while no settled row would change.
     */
    @Transactional
    public ResultPreviewResponse correct(AuthenticatedUser actor, Long contestId, DeclareResultRequest request) {
        Contest contest = contests.findByIdForUpdate(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
        if (request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("A reason is required to correct a result");
        }
        if (contest.getResultType() == null) {
            throw new ConflictException("This contest has no result to correct");
        }
        if (contest.getContestStatus() == ContestStatus.CANCELLED) {
            throw new ConflictException("A cancelled contest has no result to correct");
        }

        ActorType actorType = correctionActorType(actor, contest, request.reason());
        requireCorrectionIsStillPossible(contest);

        Map<String, Object> before = Map.of(
                "resultType", String.valueOf(contest.getResultType()),
                "winningTeamId", String.valueOf(contest.getWinningTeam() == null ? null : contest.getWinningTeam().getId()),
                "leftoverAmount", contest.getLeftoverAmount().toPlainString());

        Long winningTeamId = resolveWinningTeam(contest, request);
        List<ContestParticipant> confirmed = confirmedParticipants(contestId);
        PayoutCalculator.Settlement settlement =
                PayoutCalculator.compute(contest.getContestAmount(), winningTeamId, toPicks(confirmed));

        // A standalone contest's payouts are replaced. Nobody else has been paid (checked above), so the only
        // paid-out row left is the organizer's automatic one, which is replaced too.
        if (contest.isStandalone()) {
            payoutService.voidPayoutsForCorrection(contest);
        }
        apply(contest, winningTeamId, request.noResult(), confirmed, settlement, actor);

        // In a league that has already ended, the balances were frozen. The ones nobody has settled yet are redone.
        if (contest.getLeague() != null && contest.getLeague().getStatus() == LeagueStatus.PENDING_SETTLEMENT) {
            // The recalculation reads through plain JDBC, which does not trigger Hibernate's auto-flush. Without
            // this the corrected results would still be sitting in the session and the old figures would be read.
            participants.flush();
            settlementService.recalculatePendingRows(contest.getLeague().getId());
        }

        auditService.record(AuditEntry.builder()
                .action(AuditAction.RESULT_CORRECTED)
                .entityType("contest").entityId(contestId)
                .leagueId(contest.getLeague() == null ? null : contest.getLeague().getId())
                .contestId(contestId)
                .actorId(actor.id()).actorType(actorType)
                .reason(request.reason())
                .before(before)
                .after(resultSnapshot(contest, settlement))
                .build());

        announce(contest, settlement, confirmed, NotificationType.RESULT_CORRECTED,
                "The result was corrected");
        return toPreview(contest, winningTeamId, request.noResult(), confirmed, settlement);
    }

    // ---------- read ----------

    /** The settled outcomes of a contest, once it has a result. */
    @Transactional(readOnly = true)
    public List<ParticipantOutcomeResponse> outcomes(AuthenticatedUser actor, Long contestId) {
        Contest contest = loadForDetails(contestId);
        if (contest.getLeague() != null) {
            accessGuard.requireLeagueMember(actor, contest.getLeague());
        }
        if (contest.getResultType() == null) {
            throw new ConflictException("This contest has no result yet");
        }
        Map<Long, String> codes = teamCodes(contest);
        return participants.findByStatusWithUser(contestId, ParticipantStatus.CONFIRMED).stream()
                .map(p -> new ParticipantOutcomeResponse(
                        p.getId(), p.getUser().getId(), p.getUser().getDisplayName(),
                        p.getSelectedTeam() == null ? null : p.getSelectedTeam().getId(),
                        p.getSelectedTeam() == null ? null : codes.get(p.getSelectedTeam().getId()),
                        p.getResult() == null ? null : p.getResult().name(),
                        p.getEntrySpent(), p.getWinningAmount(),
                        p.getWinningAmount().subtract(p.getEntrySpent())))
                .sorted(Comparator.comparing(ParticipantOutcomeResponse::netPl).reversed())
                .toList();
    }

    // ---------- the shared write ----------

    private void apply(Contest contest, Long winningTeamId, boolean noResult,
                       List<ContestParticipant> confirmed, PayoutCalculator.Settlement settlement,
                       AuthenticatedUser actor) {
        Map<Long, PayoutCalculator.Outcome> byParticipant = new LinkedHashMap<>();
        for (PayoutCalculator.Outcome o : settlement.outcomes()) {
            byParticipant.put(o.participantId(), o);
        }
        for (ContestParticipant p : confirmed) {
            PayoutCalculator.Outcome outcome = byParticipant.get(p.getId());
            if (outcome == null) {
                continue;
            }
            p.setResult(outcome.result());
            p.setEntrySpent(outcome.entrySpent());
            p.setWinningAmount(outcome.winnings());
        }
        participants.saveAll(confirmed);

        contest.setResultType(noResult || winningTeamId == null ? ResultType.NO_RESULT : ResultType.WINNER);
        contest.setWinningTeam(winningTeamId == null ? null : teamOf(contest, winningTeamId));
        contest.setResultSource(ResultSource.ORGANIZER);
        contest.setResultEnteredAt(clock.instant());
        contest.setResultEnteredBy(users.getReferenceById(actor.id()));
        contest.setLeftoverAmount(settlement.leftover());
        contest.setContestStatus(ContestStatus.RESULT_DECLARED);

        if (contest.isStandalone()) {
            payoutService.createForResult(contest, confirmed, settlement);
            payoutService.refreshContestSettlement(contest);
        } else {
            // A league contest moves straight to settled: its money is netted when the league ends.
            contest.setContestStatus(ContestStatus.SETTLED);
        }
    }

    // ---------- rules ----------

    /** A result can only be entered once predictions are shut, which is what LOCKED means. */
    private static void requireReadyForResult(Contest contest) {
        switch (contest.getContestStatus()) {
            case LOCKED -> {
                // ready
            }
            case SCHEDULED -> throw new ConflictException(
                    "This contest has not been locked yet. A result can be entered once it starts");
            case CANCELLED -> throw new ConflictException("This contest was cancelled");
            default -> throw new ConflictException("This contest already has a result");
        }
    }

    /**
     * Who may correct what. The organizer may correct their own contest, and an admin may correct any contest.
     * Once a league has ended, correcting one of its contests is an admin job: the members have been told what
     * they owe, so a silent change by the organizer is not acceptable.
     */
    private ActorType correctionActorType(AuthenticatedUser actor, Contest contest, String reason) {
        boolean leagueHasEnded = contest.getLeague() != null
                && (contest.getLeague().getStatus() == LeagueStatus.PENDING_SETTLEMENT
                || contest.getLeague().getStatus() == LeagueStatus.COMPLETED);
        if (leagueHasEnded) {
            accessGuard.requireAdminWithReason(actor, reason);
            return ActorType.ADMIN;
        }
        return accessGuard.requireOrganizerOrAdmin(actor, contest, reason);
    }

    private void requireCorrectionIsStillPossible(Contest contest) {
        if (contest.isStandalone()) {
            if (payouts.existsPaidOutToSomeoneElse(contest.getId(), contest.getOrganizer().getId())) {
                throw new ConflictException(
                        "A payout has already been paid out. Mark it as not paid first, then correct the result");
            }
            return;
        }
        // In a league, a member who has already handed money over must not have the figure changed under them.
        Long leagueId = contest.getLeague().getId();
        if (settlementRows.existsSettledForSomeoneOtherThanTheOrganizer(leagueId, SettlementStatus.SETTLED)) {
            throw new ConflictException(
                    "Some members of this league have already settled up. Their balances cannot be changed");
        }
    }

    private Long resolveWinningTeam(Contest contest, DeclareResultRequest request) {
        if (request.noResult()) {
            if (request.winningTeamId() != null) {
                throw new BadRequestException("Send either a winning team or noResult, not both");
            }
            return null;
        }
        Long teamId = request.winningTeamId();
        if (teamId == null) {
            throw new BadRequestException("Send the winning team, or noResult for an abandoned match");
        }
        if (!teamId.equals(contest.getTeamA().getId()) && !teamId.equals(contest.getTeamB().getId())) {
            throw new BadRequestException("That team is not playing in this contest");
        }
        return teamId;
    }

    // ---------- the preview token ----------

    /**
     * A fingerprint of the result and of every pick that feeds the sums. Confirm recomputes it, so a pick that
     * changed between preview and confirm produces a different token and the confirmation is refused.
     */
    private static String previewToken(Contest contest, Long winningTeamId, List<ContestParticipant> confirmed) {
        StringBuilder material = new StringBuilder()
                .append(contest.getId()).append('|')
                .append(contest.getContestAmount().toPlainString()).append('|')
                .append(winningTeamId == null ? "NO_RESULT" : winningTeamId).append('|');
        confirmed.stream()
                .sorted(Comparator.comparing(ContestParticipant::getId))
                .forEach(p -> material.append(p.getId()).append(':')
                        .append(p.getSelectedTeam() == null ? "-" : p.getSelectedTeam().getId()).append(','));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    private static void requireMatchingToken(String supplied, Contest contest, Long winningTeamId,
                                             List<ContestParticipant> confirmed) {
        if (supplied == null || supplied.isBlank()) {
            throw new BadRequestException("Preview the result first, then confirm it with the preview token");
        }
        String expected = previewToken(contest, winningTeamId, confirmed);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ConflictException(
                    "Something changed since the preview. Preview the result again and check the figures");
        }
    }

    // ---------- plumbing ----------

    private Contest loadForDetails(Long contestId) {
        return contests.findByIdWithDetails(contestId)
                .orElseThrow(() -> new NotFoundException("Contest not found"));
    }

    private List<ContestParticipant> confirmedParticipants(Long contestId) {
        return participants.findByStatusWithUser(contestId, ParticipantStatus.CONFIRMED);
    }

    private static List<PayoutCalculator.Pick> toPicks(List<ContestParticipant> confirmed) {
        List<PayoutCalculator.Pick> picks = new ArrayList<>(confirmed.size());
        for (ContestParticipant p : confirmed) {
            picks.add(new PayoutCalculator.Pick(p.getId(), p.getUser().getId(),
                    p.getSelectedTeam() == null ? null : p.getSelectedTeam().getId()));
        }
        return picks;
    }

    private static Team teamOf(Contest contest, Long teamId) {
        return teamId.equals(contest.getTeamA().getId()) ? contest.getTeamA() : contest.getTeamB();
    }

    private static Map<Long, String> teamCodes(Contest contest) {
        Map<Long, String> codes = new LinkedHashMap<>();
        codes.put(contest.getTeamA().getId(), contest.getTeamA().getShortCode());
        codes.put(contest.getTeamB().getId(), contest.getTeamB().getShortCode());
        return codes;
    }

    private static String label(Contest contest) {
        if (contest.getName() != null && !contest.getName().isBlank()) {
            return contest.getName();
        }
        return contest.getTeamA().getShortCode() + " vs " + contest.getTeamB().getShortCode();
    }

    private static Map<String, Object> resultSnapshot(Contest contest, PayoutCalculator.Settlement settlement) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("resultType", contest.getResultType().name());
        after.put("winningTeamId", contest.getWinningTeam() == null ? null : contest.getWinningTeam().getId());
        after.put("pool", settlement.pool().toPlainString());
        after.put("winnerCount", settlement.winnerCount());
        after.put("perWinner", settlement.perWinner().toPlainString());
        after.put("leftoverAmount", settlement.leftover().toPlainString());
        after.put("everyoneRefunded", settlement.refundAll());
        return after;
    }

    private void announce(Contest contest, PayoutCalculator.Settlement settlement,
                          List<ContestParticipant> confirmed, NotificationType type, String title) {
        String label = label(contest);
        String winner = contest.getWinningTeam() == null ? "No result" : contest.getWinningTeam().getShortCode();
        Map<Long, PayoutCalculator.Outcome> byParticipant = new LinkedHashMap<>();
        for (PayoutCalculator.Outcome o : settlement.outcomes()) {
            byParticipant.put(o.participantId(), o);
        }
        for (ContestParticipant p : confirmed) {
            PayoutCalculator.Outcome outcome = byParticipant.get(p.getId());
            String yours = outcome == null ? "" : switch (outcome.result()) {
                case WON -> " You won " + outcome.winnings().toPlainString() + ".";
                case LOST -> " You lost your entry of " + outcome.entrySpent().toPlainString() + ".";
                case REFUNDED -> " Everyone was refunded.";
            };
            notificationService.notifyNow(p.getUser().getId(), type,
                    ReferenceType.CONTEST, contest.getId(),
                    title + ": " + label,
                    winner + "." + yours);
        }
    }

    private ResultPreviewResponse toPreview(Contest contest, Long winningTeamId, boolean noResult,
                                            List<ContestParticipant> confirmed,
                                            PayoutCalculator.Settlement settlement) {
        Map<Long, String> codes = teamCodes(contest);
        Map<Long, PayoutCalculator.Outcome> byParticipant = new LinkedHashMap<>();
        for (PayoutCalculator.Outcome o : settlement.outcomes()) {
            byParticipant.put(o.participantId(), o);
        }
        List<ParticipantOutcomeResponse> rows = new ArrayList<>(confirmed.size());
        for (ContestParticipant p : confirmed) {
            PayoutCalculator.Outcome outcome = byParticipant.get(p.getId());
            BigDecimal spent = outcome == null ? BigDecimal.ZERO : outcome.entrySpent();
            BigDecimal won = outcome == null ? BigDecimal.ZERO : outcome.winnings();
            rows.add(new ParticipantOutcomeResponse(
                    p.getId(), p.getUser().getId(), p.getUser().getDisplayName(),
                    p.getSelectedTeam() == null ? null : p.getSelectedTeam().getId(),
                    p.getSelectedTeam() == null ? null : codes.get(p.getSelectedTeam().getId()),
                    outcome == null ? null : outcome.result().name(),
                    spent, won, won.subtract(spent)));
        }
        rows.sort(Comparator.comparing(ParticipantOutcomeResponse::netPl).reversed());

        return new ResultPreviewResponse(
                contest.getId(), label(contest),
                winningTeamId, winningTeamId == null ? null : codes.get(winningTeamId),
                noResult || winningTeamId == null,
                contest.getContestAmount(), confirmed.size(),
                settlement.pool(), settlement.winnerCount(), settlement.perWinner(), settlement.leftover(),
                settlement.refundAll(), refundReason(winningTeamId, confirmed.size(), settlement),
                List.copyOf(rows),
                previewToken(contest, winningTeamId, confirmed));
    }

    private static String refundReason(Long winningTeamId, int confirmedCount,
                                       PayoutCalculator.Settlement settlement) {
        if (!settlement.refundAll()) {
            return null;
        }
        if (confirmedCount == 0) {
            return "There were no confirmed participants";
        }
        if (winningTeamId == null) {
            return "There was no result, so every entry is refunded";
        }
        if (settlement.winnerCount() == 0) {
            return "Nobody picked the winning team, so every entry is refunded";
        }
        return "Everybody picked the winning team, so there is nothing to split and every entry is refunded";
    }
}
