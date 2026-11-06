package com.sahilprojects.bettingApp.dashboard;

import com.sahilprojects.bettingApp.dashboard.dto.PeriodStatsResponse;
import com.sahilprojects.bettingApp.dashboard.dto.PeriodStatsResponse.Breakdown;
import com.sahilprojects.bettingApp.dashboard.dto.PeriodStatsResponse.MonthLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Works a user's record out of their contest rows for a chosen period. A pure function, like PayoutCalculator: no
 * database and no clock, so every figure is easy to test.
 *
 * Money in is entry_spent on contests that were decided (won or lost); refunded contests cost nothing and are
 * counted separately, not as played. Months are calendar months in India time, since that is where the players are.
 */
public final class PeriodStats {

    public static final ZoneId PLAYERS_ZONE = ZoneId.of("Asia/Kolkata");

    private PeriodStats() {
    }

    public static PeriodStatsResponse compute(StatsRange range, Instant from, Instant to, List<MyResultRow> rows) {
        List<MyResultRow> sorted = rows.stream().sorted(Comparator.comparing(MyResultRow::startTime)).toList();

        int won = 0, lost = 0, refunded = 0, pending = 0, leagueGames = 0, oneOffGames = 0;
        BigDecimal invested = BigDecimal.ZERO, winnings = BigDecimal.ZERO;
        BigDecimal leaguePl = BigDecimal.ZERO, oneOffPl = BigDecimal.ZERO;
        BigDecimal biggestWin = BigDecimal.ZERO;
        int streak = 0, bestStreak = 0, current = 0;
        Set<Long> leagues = new HashSet<>();
        Map<YearMonth, MonthAcc> months = new TreeMap<>();
        KindAcc leagueKind = new KindAcc(), oneOffKind = new KindAcc();

        for (MyResultRow r : sorted) {
            KindAcc kind = r.leagueId() != null ? leagueKind : oneOffKind;
            if (r.leagueId() != null) leagues.add(r.leagueId());
            if (r.result() == null) {
                pending++;
                kind.pending++;
                continue;
            }
            if ("REFUNDED".equals(r.result())) {
                refunded++;
                kind.refunded++;
                continue;
            }
            boolean win = "WON".equals(r.result());
            if (win) won++; else lost++;
            invested = invested.add(r.entrySpent());
            winnings = winnings.add(r.winningAmount());
            BigDecimal net = r.net();
            kind.add(win, r.entrySpent(), r.winningAmount());
            if (r.leagueId() != null) {
                leagueGames++;
                leaguePl = leaguePl.add(net);
            } else {
                oneOffGames++;
                oneOffPl = oneOffPl.add(net);
            }
            if (net.compareTo(biggestWin) > 0) biggestWin = net;

            streak = win ? streak + 1 : 0;
            bestStreak = Math.max(bestStreak, streak);
            current = win ? (current > 0 ? current + 1 : 1) : (current < 0 ? current - 1 : -1);

            MonthAcc m = months.computeIfAbsent(YearMonth.from(r.startTime().atZone(PLAYERS_ZONE)), k -> new MonthAcc());
            m.played++;
            if (win) m.won++;
            m.invested = m.invested.add(r.entrySpent());
            m.netPl = m.netPl.add(net);
        }

        int played = won + lost;
        BigDecimal netPl = winnings.subtract(invested);
        List<MonthLine> monthLines = months.entrySet().stream()
                .map(e -> new MonthLine(e.getKey().toString(), e.getValue().played, e.getValue().won,
                        e.getValue().invested, e.getValue().netPl))
                .toList();

        return new PeriodStatsResponse(range.name(), from, to,
                played, won, lost, refunded, pending,
                percentage(BigDecimal.valueOf(won), BigDecimal.valueOf(played)),
                invested, winnings, netPl,
                percentage(netPl, invested),
                biggestWin, bestStreak, current,
                leagues.size(), leagueGames, oneOffGames, leaguePl, oneOffPl,
                leagueKind.toBreakdown(), oneOffKind.toBreakdown(),
                monthLines);
    }

    /** part ÷ whole as a percentage with one decimal, zero when there is nothing to divide by. */
    static BigDecimal percentage(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) return BigDecimal.ZERO.setScale(1);
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** The same record for one kind of game: leagues, or one-off contests. */
    private static final class KindAcc {
        int won;
        int lost;
        int refunded;
        int pending;
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal winnings = BigDecimal.ZERO;

        void add(boolean win, BigDecimal spent, BigDecimal winning) {
            if (win) won++; else lost++;
            invested = invested.add(spent);
            winnings = winnings.add(winning);
        }

        Breakdown toBreakdown() {
            int played = won + lost;
            BigDecimal net = winnings.subtract(invested);
            return new Breakdown(played, won, lost, refunded, pending,
                    percentage(BigDecimal.valueOf(won), BigDecimal.valueOf(played)),
                    invested, winnings, net, percentage(net, invested));
        }
    }

    private static final class MonthAcc {
        int played;
        int won;
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal netPl = BigDecimal.ZERO;
    }
}
