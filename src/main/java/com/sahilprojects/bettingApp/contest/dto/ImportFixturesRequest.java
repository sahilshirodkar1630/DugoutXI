package com.sahilprojects.bettingApp.contest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** dryRun checks every row and creates nothing, so the organizer sees what will happen before committing. */
public record ImportFixturesRequest(@NotEmpty @Size(max = 200) List<@Valid FixtureRow> rows, boolean dryRun) {
}
