package com.sahilprojects.bettingApp.contest.dto;

import java.util.List;

/**
 * The import is all or nothing: ok is true only when every row passed, and created counts the contests made (zero
 * on a dry run or when any row failed).
 */
public record ImportFixturesResponse(boolean ok, boolean dryRun, int created, List<FixtureRowResult> rows) {
}
