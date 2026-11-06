package com.sahilprojects.bettingApp.payout;

import com.sahilprojects.bettingApp.contest.ParticipantResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The money rules for one contest, as a pure function. No database, no clock, no Spring: give it the entry amount,
 * the winning team and the confirmed participants' picks, and it tells you what every participant gets.
 *
 * <pre>
 *   pool       = amount x number of confirmed participants
 *   per winner = floor(pool / number of winners) to 2 decimal places
 *   leftover   = pool - (per winner x number of winners)      (kept on the contest, not paid out)
 * </pre>
 *
 * Everyone is refunded, and nobody's entry is spent, when there is no result, when nobody picked the winning team,
 * or when everybody did. In every other case each confirmed participant spends their entry: the ones who picked the
 * winning team get an equal share of the pool, and the rest get nothing. A participant who never made a pick is
 * simply a non-winner: their entry still goes into the pool.
 */
public final class PayoutCalculator {

    private PayoutCalculator() {
    }

    /** One confirmed participant's pick. selectedTeamId is null when they never predicted. */
    public record Pick(long participantId, long userId, Long selectedTeamId) {
    }

    /** What one participant ends up with. */
    public record Outcome(long participantId, long userId, ParticipantResult result,
                          BigDecimal entrySpent, BigDecimal winnings) {

        public boolean won() {
            return result == ParticipantResult.WON;
        }
    }

    /** The whole contest's money, ready to be written to the database. */
    public record Settlement(List<Outcome> outcomes, BigDecimal pool, BigDecimal perWinner,
                             BigDecimal leftover, int winnerCount, boolean refundAll) {

        public List<Outcome> winners() {
            return outcomes.stream().filter(Outcome::won).toList();
        }
    }

    private static final BigDecimal ZERO = money(BigDecimal.ZERO);

    /**
     * @param amount         the contest entry amount, which must be positive
     * @param winningTeamId  the team that won, or null for a no-result contest
     * @param picks          every CONFIRMED participant, whether or not they predicted
     */
    public static Settlement compute(BigDecimal amount, Long winningTeamId, Collection<Pick> picks) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("The contest amount must be positive");
        }
        List<Pick> all = List.copyOf(picks);
        if (all.isEmpty()) {
            return new Settlement(List.of(), ZERO, ZERO, ZERO, 0, true);
        }

        BigDecimal entry = money(amount);
        BigDecimal pool = money(entry.multiply(BigDecimal.valueOf(all.size())));
        long winnerCount = winningTeamId == null ? 0
                : all.stream().filter(p -> winningTeamId.equals(p.selectedTeamId())).count();

        // Nobody won, or everybody did: the pool cannot be split, so nothing is spent and nothing is paid.
        if (winnerCount == 0 || winnerCount == all.size()) {
            List<Outcome> refunds = new ArrayList<>(all.size());
            for (Pick p : all) {
                refunds.add(new Outcome(p.participantId(), p.userId(), ParticipantResult.REFUNDED, ZERO, ZERO));
            }
            return new Settlement(List.copyOf(refunds), pool, ZERO, ZERO, (int) winnerCount, true);
        }

        BigDecimal perWinner = pool.divide(BigDecimal.valueOf(winnerCount), 2, RoundingMode.DOWN);
        BigDecimal leftover = money(pool.subtract(perWinner.multiply(BigDecimal.valueOf(winnerCount))));

        List<Outcome> outcomes = new ArrayList<>(all.size());
        for (Pick p : all) {
            boolean won = winningTeamId.equals(p.selectedTeamId());
            outcomes.add(new Outcome(p.participantId(), p.userId(),
                    won ? ParticipantResult.WON : ParticipantResult.LOST,
                    entry,
                    won ? perWinner : ZERO));
        }
        return new Settlement(List.copyOf(outcomes), pool, perWinner, leftover, (int) winnerCount, false);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
