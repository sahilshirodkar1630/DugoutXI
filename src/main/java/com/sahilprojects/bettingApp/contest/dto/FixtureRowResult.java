package com.sahilprojects.bettingApp.contest.dto;

import java.time.Instant;

/** What happened to one row: the match it became, or why it was refused. row counts from 1. */
public record FixtureRowResult(
        int row,
        TeamRef teamA,
        TeamRef teamB,
        Instant startTime,
        String name,
        Integer contestNumber,
        Long contestId,
        String error) {
}
