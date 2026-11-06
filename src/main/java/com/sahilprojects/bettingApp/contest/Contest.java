package com.sahilprojects.bettingApp.contest;

import com.sahilprojects.bettingApp.common.BaseEntity;
import com.sahilprojects.bettingApp.league.League;
import com.sahilprojects.bettingApp.tournament.Team;
import com.sahilprojects.bettingApp.tournament.Tournament;
import com.sahilprojects.bettingApp.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "contests")
@Getter
@Setter
public class Contest extends BaseEntity {

    /** Null means a standalone contest. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "league_id")
    private League league;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizer_id", nullable = false)
    private User organizer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    private String name;

    /** Display only ("Match 12 of 74"). League contests only. */
    @Column(name = "contest_number")
    private Integer contestNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_a_id", nullable = false)
    private Team teamA;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_b_id", nullable = false)
    private Team teamB;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "contest_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal contestAmount;

    // Standalone contests only
    @Column(name = "registration_deadline")
    private Instant registrationDeadline;

    @Column(name = "payment_deadline")
    private Instant paymentDeadline;

    @Column(name = "payment_instructions")
    private String paymentInstructions;

    @Enumerated(EnumType.STRING)
    @Column(name = "contest_status", nullable = false)
    private ContestStatus contestStatus = ContestStatus.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type")
    private ResultType resultType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winning_team_id")
    private Team winningTeam;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_source")
    private ResultSource resultSource;

    @Column(name = "result_entered_at")
    private Instant resultEnteredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_entered_by")
    private User resultEnteredBy;

    @Column(name = "leftover_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal leftoverAmount = BigDecimal.ZERO;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "cancelled_reason")
    private String cancelledReason;

    public boolean isStandalone() {
        return league == null;
    }
}
