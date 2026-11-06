package com.sahilprojects.bettingApp.dashboard;

import com.sahilprojects.bettingApp.dashboard.dto.PeriodStatsResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The profile's record for a period, worked out without a database. */
class PeriodStatsTest {

    private static final Instant APRIL_5 = Instant.parse("2026-04-05T14:00:00Z");
    private static final Instant APRIL_30_NIGHT = Instant.parse("2026-04-30T20:00:00Z");   // 1 May in India
    private static final Instant MAY_10 = Instant.parse("2026-05-10T14:00:00Z");

    @Test
    void nothingPlayedIsAllZeroes() {
        PeriodStatsResponse s = PeriodStats.compute(StatsRange.ALL, null, MAY_10, List.of());
        assertThat(s.contestsPlayed()).isZero();
        assertThat(s.winPercentage()).isEqualByComparingTo("0");
        assertThat(s.returnPercentage()).isEqualByComparingTo("0");
        assertThat(s.months()).isEmpty();
    }

    @Test
    void moneyInWinningsAndProfitAddUp() {
        PeriodStatsResponse s = PeriodStats.compute(StatsRange.ALL, null, MAY_10, List.of(
                row(APRIL_5, 1L, "WON", "100", "300"),
                row(APRIL_5.plusSeconds(86400), 1L, "LOST", "100", "0"),
                row(MAY_10, null, "LOST", "50", "0"),
                row(MAY_10.plusSeconds(60), null, "REFUNDED", "0", "0"),
                row(MAY_10.plusSeconds(120), 1L, null, "0", "0")));

        assertThat(s.contestsPlayed()).isEqualTo(3);
        assertThat(s.contestsWon()).isEqualTo(1);
        assertThat(s.contestsLost()).isEqualTo(2);
        assertThat(s.contestsRefunded()).isEqualTo(1);
        assertThat(s.contestsPending()).isEqualTo(1);
        assertThat(s.amountInvested()).isEqualByComparingTo("250");
        assertThat(s.amountWon()).isEqualByComparingTo("300");
        assertThat(s.netPl()).isEqualByComparingTo("50");
        assertThat(s.returnPercentage()).isEqualByComparingTo("20.0");
        assertThat(s.winPercentage()).isEqualByComparingTo("33.3");
        assertThat(s.biggestWin()).isEqualByComparingTo("200");
        assertThat(s.leaguesPlayed()).isEqualTo(1);
        assertThat(s.leagueContestsPlayed()).isEqualTo(2);
        assertThat(s.oneOffContestsPlayed()).isEqualTo(1);
        assertThat(s.leagueNetPl()).isEqualByComparingTo("100");
        assertThat(s.oneOffNetPl()).isEqualByComparingTo("-50");
    }

    @Test
    void streaksCountRunsInStartTimeOrder() {
        PeriodStatsResponse s = PeriodStats.compute(StatsRange.ALL, null, MAY_10, List.of(
                row(APRIL_5.plusSeconds(5), null, "WON", "10", "20"),
                row(APRIL_5, null, "LOST", "10", "0"),
                row(APRIL_5.plusSeconds(10), null, "WON", "10", "20"),
                row(APRIL_5.plusSeconds(20), null, "REFUNDED", "0", "0"),
                row(APRIL_5.plusSeconds(30), null, "WON", "10", "20"),
                row(APRIL_5.plusSeconds(40), null, "LOST", "10", "0"),
                row(APRIL_5.plusSeconds(50), null, "LOST", "10", "0")));

        assertThat(s.bestStreak()).isEqualTo(3);     // a refund does not break a run
        assertThat(s.currentStreak()).isEqualTo(-2);
    }

    @Test
    void monthsFollowIndiaTime() {
        PeriodStatsResponse s = PeriodStats.compute(StatsRange.ALL, null, MAY_10, List.of(
                row(APRIL_5, null, "WON", "100", "150"),
                row(APRIL_30_NIGHT, null, "LOST", "100", "0"),
                row(MAY_10, null, "LOST", "100", "0")));

        assertThat(s.months()).hasSize(2);
        assertThat(s.months().get(0).month()).isEqualTo("2026-04");
        assertThat(s.months().get(0).played()).isEqualTo(1);
        assertThat(s.months().get(0).netPl()).isEqualByComparingTo("50");
        assertThat(s.months().get(1).month()).isEqualTo("2026-05");
        assertThat(s.months().get(1).played()).isEqualTo(2);
        assertThat(s.months().get(1).invested()).isEqualByComparingTo("200");
    }

    @Test
    void aRangeStartsThatManyMonthsBackAndAllHasNoStart() {
        Instant now = Instant.parse("2026-11-06T06:00:00Z");
        assertThat(StatsRange.ONE_MONTH.startFrom(now)).isEqualTo(Instant.parse("2026-10-06T06:00:00Z"));
        assertThat(StatsRange.ONE_YEAR.startFrom(now)).isEqualTo(Instant.parse("2025-11-06T06:00:00Z"));
        assertThat(StatsRange.ALL.startFrom(now)).isNull();
    }

    private static MyResultRow row(Instant start, Long leagueId, String result, String spent, String won) {
        return new MyResultRow(1, start, leagueId, leagueId == null ? null : "League", null, null, "MI", "CSK",
                "RESULT_DECLARED", "MI", "MI", result, new BigDecimal(spent), new BigDecimal(won));
    }
}
