package com.sahilprojects.bettingApp.settlement;

import com.sahilprojects.bettingApp.common.BaseEntity;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.league.LeagueMember;
import com.sahilprojects.bettingApp.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** One row per paid league member, created when the league ends. final_balance = joining_amount_paid + net_pl. */
@Entity
@Table(name = "settlement_rows",
        uniqueConstraints = @UniqueConstraint(columnNames = {"league_id", "league_member_id"}))
@Getter
@Setter
public class SettlementRow extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "league_id", nullable = false)
    private League league;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "league_member_id", nullable = false)
    private LeagueMember leagueMember;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "is_organizer", nullable = false)
    private boolean organizer;

    @Column(name = "joining_amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal joiningAmountPaid;

    @Column(name = "net_pl", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPl;

    @Column(name = "final_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal finalBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false)
    private SettlementStatus settlementStatus = SettlementStatus.PENDING;

    @Column(name = "settled_at")
    private Instant settledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settled_by")
    private User settledBy;

    @Column(name = "written_off_reason")
    private String writtenOffReason;
}
