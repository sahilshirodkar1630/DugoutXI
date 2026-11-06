package com.sahilprojects.bettingApp.payout;

import com.sahilprojects.bettingApp.common.BaseEntity;
import com.sahilprojects.bettingApp.contest.Contest;
import com.sahilprojects.bettingApp.contest.ContestParticipant;
import com.sahilprojects.bettingApp.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Standalone contests only. League contests create no payouts. */
@Entity
@Table(name = "payouts")
@Getter
@Setter
public class Payout extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_participant_id", nullable = false)
    private ContestParticipant contestParticipant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_type", nullable = false)
    private PayoutType payoutType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_status", nullable = false)
    private PayoutStatus payoutStatus = PayoutStatus.PENDING;

    @Column(name = "paid_out_at")
    private Instant paidOutAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_out_by")
    private User paidOutBy;
}
