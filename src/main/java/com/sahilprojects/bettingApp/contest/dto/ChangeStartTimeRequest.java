package com.sahilprojects.bettingApp.contest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** reason is only mandatory when an admin acts for the organizer. */
public record ChangeStartTimeRequest(@NotNull java.time.Instant startTime, @Size(max = 500) String reason) {
}
