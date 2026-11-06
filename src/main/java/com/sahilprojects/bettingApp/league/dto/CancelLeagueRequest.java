package com.sahilprojects.bettingApp.league.dto;

import jakarta.validation.constraints.Size;

/** The reason is optional for the organizer and mandatory for an admin acting on someone else's league. */
public record CancelLeagueRequest(@Size(max = 500) String reason) {
}
