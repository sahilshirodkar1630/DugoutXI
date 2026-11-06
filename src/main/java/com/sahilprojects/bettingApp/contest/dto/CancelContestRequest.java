package com.sahilprojects.bettingApp.contest.dto;

import jakarta.validation.constraints.Size;

public record CancelContestRequest(@Size(max = 500) String reason) {
}
