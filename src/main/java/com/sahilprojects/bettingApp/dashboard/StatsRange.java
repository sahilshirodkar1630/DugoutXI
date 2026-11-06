package com.sahilprojects.bettingApp.dashboard;

import java.time.Instant;
import java.time.ZonedDateTime;

/** The periods the profile offers. ALL has no start. */
public enum StatsRange {
    ONE_MONTH(1), THREE_MONTHS(3), SIX_MONTHS(6), ONE_YEAR(12), ALL(0);

    private final int months;

    StatsRange(int months) {
        this.months = months;
    }

    /** The start of the period, counted back from now in India time; null for ALL. */
    public Instant startFrom(Instant now) {
        if (months == 0) return null;
        return ZonedDateTime.ofInstant(now, PeriodStats.PLAYERS_ZONE).minusMonths(months).toInstant();
    }
}
