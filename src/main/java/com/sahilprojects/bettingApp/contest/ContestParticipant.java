package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.common.BaseEntity;
import com.sahilprojects.bettingApp.participation.Participation;
import com.sahilprojects.bettingApp.participation.ParticipationKind;
import com.sahilprojects.bettingApp.league.LeagueMember;
import com.sahilprojects.bettingApp.tournament.Team;
import com.sahilprojects.bettingApp.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per user per contest: membership, prediction and result together.
 * Never return selectedTeam of another user before the contest starts. Use a DTO.
 */
@Entity
@Table(name = "contest_participants",
        uniqueConstraints = @UniqueConstraint(columnNames = {"contest_id", "user_id"}))
@Getter
@Setter
public class ContestParticipant extends BaseEntity implements Participation {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Set for league contests, null for standalone. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "league_member_id")
    private LeagueMember leagueMember;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_status", nullable = false)
    private ParticipantStatus participantStatus = ParticipantStatus.PENDING_APPROVAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private ContestPaymentStatus paymentStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_team_id")
    private Team selectedTeam;

    @Column(name = "predicted_at")
    private Instant predictedAt;

    @Enumerated(EnumType.STRING)
    private ParticipantResult result;

    @Column(name = "entry_spent", nullable = false, precision = 10, scale = 2)
    private BigDecimal entrySpent = BigDecimal.ZERO;

    @Column(name = "winning_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal winningAmount = BigDecimal.ZERO;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @PrePersist
    void onParticipantCreate() {
        if (requestedAt == null) requestedAt = Instant.now();
    }

    // ---------- Participation (the shared status workflow, standalone contests) ----------

    @Override
    public ParticipationKind kind() {
        return ParticipationKind.CONTEST_PARTICIPANT;
    }

    @Override
    public Long leagueIdForAudit() {
        return contest.getLeague() == null ? null : contest.getLeague().getId();
    }

    @Override
    public Long contestIdForAudit() {
        return contest.getId();
    }

    @Override
    public Long participantUserId() {
        return user.getId();
    }

    @Override
    public String currentStatus() {
        return participantStatus.name();
    }

    @Override
    public void changeStatus(String newStatus) {
        this.participantStatus = ParticipantStatus.valueOf(newStatus);
    }

    /** A league contest is paid through the league's joining amount, so it counts as paid. */
    @Override
    public boolean isPaid() {
        return paymentStatus == ContestPaymentStatus.PAID || paymentStatus == ContestPaymentStatus.NOT_REQUIRED;
    }

    @Override
    public void recordPaid(Instant at) {
        requireStandalone();
        this.paymentStatus = ContestPaymentStatus.PAID;
    }

    @Override
    public void recordUnpaid() {
        requireStandalone();
        this.paymentStatus = ContestPaymentStatus.PENDING;
    }

    private void requireStandalone() {
        if (paymentStatus == ContestPaymentStatus.NOT_REQUIRED) {
            throw new IllegalStateException("A league contest participant has no separate payment");
        }
    }
}
