package com.sahilprojects.bettingApp.league.dto;

import jakarta.validation.constraints.Size;

/** An optional reason. It is mandatory only when an admin acts for the organizer. */
public record ReasonRequest(@Size(max = 500) String reason) {
}
