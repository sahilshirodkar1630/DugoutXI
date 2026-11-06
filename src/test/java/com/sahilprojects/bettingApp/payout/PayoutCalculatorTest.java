package com.sahilprojects.bettingApp.payout;

import com.sahilprojects.bettingApp.contest.ParticipantResult;
import com.sahilprojects.bettingApp.payout.PayoutCalculator.Outcome;
import com.sahilprojects.bettingApp.payout.PayoutCalculator.Pick;
import com.sahilprojects.bettingApp.payout.PayoutCalculator.Settlement;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The money rules, with no database in the way. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PayoutCalculatorTest {

    private static final long TEAM_A = 1L;
    private static final long TEAM_B = 2L;

    @Test
    void theWinnersSplitThePoolEqually() {
        // 4 players at 100 each: a 400 pool shared by the 2 who picked team A.
        Settlement s = PayoutCalculator.compute(amount(100), TEAM_A, List.of(
                pick(1, TEAM_A), pick(2, TEAM_A), pick(3, TEAM_B), pick(4, TEAM_B)));

        assertThat(s.refundAll()).isFalse();
        assertThat(s.pool()).isEqualByComparingTo("400.00");
        assertThat(s.perWinner()).isEqualByComparingTo("200.00");
        assertThat(s.leftover()).isEqualByComparingTo("0.00");
        assertThat(s.winnerCount()).isEqualTo(2);

        Map<Long, Outcome> byId = byParticipant(s);
        assertThat(byId.get(1L).result()).isEqualTo(ParticipantResult.WON);
        assertThat(byId.get(1L).winnings()).isEqualByComparingTo("200.00");
        assertThat(byId.get(1L).entrySpent()).isEqualByComparingTo("100.00");
        assertThat(byId.get(3L).result()).isEqualTo(ParticipantResult.LOST);
        assertThat(byId.get(3L).winnings()).isEqualByComparingTo("0.00");
        assertThat(byId.get(3L).entrySpent()).isEqualByComparingTo("100.00");
    }

    @Test
    void anUnevenSplitRoundsDownAndKeepsTheRemainderAsLeftover() {
        // 4 players at 100 each: a 400 pool shared by 3 winners is 133.33 each, leaving 0.01.
        Settlement s = PayoutCalculator.compute(amount(100), TEAM_A, List.of(
                pick(1, TEAM_A), pick(2, TEAM_A), pick(3, TEAM_A), pick(4, TEAM_B)));

        assertThat(s.perWinner()).isEqualByComparingTo("133.33");
        assertThat(s.leftover()).isEqualByComparingTo("0.01");
        // Nothing is created out of thin air: paid out plus leftover is exactly the pool.
        assertThat(s.perWinner().multiply(BigDecimal.valueOf(3)).add(s.leftover()))
                .isEqualByComparingTo(s.pool());
    }

    @Test
    void aParticipantWhoNeverPredictedLosesButStillFeedsThePool() {
        Settlement s = PayoutCalculator.compute(amount(50), TEAM_A, List.of(
                pick(1, TEAM_A), pick(2, null), pick(3, TEAM_B)));

        assertThat(s.pool()).isEqualByComparingTo("150.00");       // the no-pick entry counts
        assertThat(byParticipant(s).get(2L).result()).isEqualTo(ParticipantResult.LOST);
        assertThat(byParticipant(s).get(2L).entrySpent()).isEqualByComparingTo("50.00");
        assertThat(byParticipant(s).get(1L).winnings()).isEqualByComparingTo("150.00");
    }

    @Test
    void nobodyPickingTheWinnerRefundsEveryone() {
        Settlement s = PayoutCalculator.compute(amount(100), TEAM_A, List.of(
                pick(1, TEAM_B), pick(2, TEAM_B), pick(3, null)));

        assertThat(s.refundAll()).isTrue();
        assertThat(s.leftover()).isEqualByComparingTo("0.00");
        assertThat(s.outcomes()).allSatisfy(o -> {
            assertThat(o.result()).isEqualTo(ParticipantResult.REFUNDED);
            assertThat(o.entrySpent()).isEqualByComparingTo("0.00");   // nothing was spent
            assertThat(o.winnings()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    void everybodyPickingTheWinnerRefundsEveryone() {
        Settlement s = PayoutCalculator.compute(amount(100), TEAM_A, List.of(
                pick(1, TEAM_A), pick(2, TEAM_A)));

        assertThat(s.refundAll()).isTrue();
        assertThat(s.outcomes()).extracting(Outcome::result)
                .containsOnly(ParticipantResult.REFUNDED);
    }

    @Test
    void aNoResultContestRefundsEveryone() {
        Settlement s = PayoutCalculator.compute(amount(100), null, List.of(
                pick(1, TEAM_A), pick(2, TEAM_B)));

        assertThat(s.refundAll()).isTrue();
        assertThat(s.winnerCount()).isZero();
        assertThat(s.outcomes()).extracting(Outcome::result)
                .containsOnly(ParticipantResult.REFUNDED);
    }

    @Test
    void aSingleWinnerTakesTheWholePool() {
        Settlement s = PayoutCalculator.compute(amount(250), TEAM_B, List.of(
                pick(1, TEAM_A), pick(2, TEAM_A), pick(3, TEAM_B)));

        assertThat(s.winners()).hasSize(1);
        assertThat(s.winners().get(0).winnings()).isEqualByComparingTo("750.00");
        assertThat(s.leftover()).isEqualByComparingTo("0.00");
    }

    @Test
    void aWinnerAlwaysGetsMoreThanZeroSoTheDatabaseCheckHolds() {
        // The database insists a WON row has winnings above zero. With at least one loser the pool is always
        // bigger than the number of winners, so the share cannot round down to nothing.
        Settlement s = PayoutCalculator.compute(amount(1), TEAM_A, List.of(
                pick(1, TEAM_A), pick(2, TEAM_B)));

        assertThat(s.perWinner()).isEqualByComparingTo("2.00");
        assertThat(s.winners()).allSatisfy(o -> assertThat(o.winnings().signum()).isPositive());
    }

    @Test
    void noConfirmedParticipantsMeansNothingToSettle() {
        Settlement s = PayoutCalculator.compute(amount(100), TEAM_A, List.of());

        assertThat(s.outcomes()).isEmpty();
        assertThat(s.refundAll()).isTrue();
        assertThat(s.pool()).isEqualByComparingTo("0.00");
    }

    @Test
    void anAmountThatIsNotPositiveIsRejected() {
        assertThatThrownBy(() -> PayoutCalculator.compute(amount(0), TEAM_A, List.of(pick(1, TEAM_A))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> PayoutCalculator.compute(null, TEAM_A, List.of(pick(1, TEAM_A))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- helpers ----------

    private static BigDecimal amount(int rupees) {
        return new BigDecimal(rupees).setScale(2);
    }

    private static Pick pick(long id, Long teamId) {
        return new Pick(id, 100 + id, teamId);
    }

    private static Map<Long, Outcome> byParticipant(Settlement s) {
        return s.outcomes().stream().collect(Collectors.toMap(Outcome::participantId, Function.identity()));
    }
}
