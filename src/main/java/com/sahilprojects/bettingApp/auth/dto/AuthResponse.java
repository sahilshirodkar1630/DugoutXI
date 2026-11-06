package com.sahilprojects.bettingApp.auth.dto;

import com.sahilprojects.bettingApp.user.dto.UserResponse;

import java.time.Instant;

public record AuthResponse(String token, Instant expiresAt, UserResponse user) {
}
