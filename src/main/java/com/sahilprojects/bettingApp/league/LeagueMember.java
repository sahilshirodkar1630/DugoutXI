package com.sahilprojects.bettingApp.league;

import com.sahilprojects.bettingApp.common.BaseEntity;
import com.sahilprojects.bettingApp.participation.Participation;
import com.sahilprojects.bettingApp.participation.ParticipationKind;
import com.sahilprojects.bettingApp.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "league_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"league_id", "user_id"}))
@Getter
@Setter
public class LeagueMember extends BaseEntity implements Participation {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "league_id", nullable = false)
    private League league;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "league_member_status", nullable = false)
    private LeagueMemberStatus leagueMemberStatus = LeagueMemberStatus.PENDING_APPROVAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @PrePersist
    void onMemberCreate() {
        if (requestedAt == null) requestedAt = Instant.now();
    }

    // ---------- Participation (the shared status workflow) ----------

    @Override
    public ParticipationKind kind() {
        return ParticipationKind.LEAGUE_MEMBER;
    }

    @Override
    public Long leagueIdForAudit() {
        return league.getId();
    }

    @Override
    public Long contestIdForAudit() {
        return null;
    }

    @Override
    public Long participantUserId() {
        return user.getId();
    }

    @Override
    public String currentStatus() {
        return leagueMemberStatus.name();
    }

    @Override
    public void changeStatus(String newStatus) {
        this.leagueMemberStatus = LeagueMemberStatus.valueOf(newStatus);
    }

    @Override
    public boolean isPaid() {
        return paymentStatus == PaymentStatus.PAID;
    }

    @Override
    public void recordPaid(Instant at) {
        this.paymentStatus = PaymentStatus.PAID;
        this.paidAt = at;
    }

    @Override
    public void recordUnpaid() {
        this.paymentStatus = PaymentStatus.PENDING;
        this.paidAt = null;
    }
}
