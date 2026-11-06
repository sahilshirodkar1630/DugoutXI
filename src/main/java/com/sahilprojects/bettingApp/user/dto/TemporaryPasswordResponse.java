package com.sahilprojects.bettingApp.user.dto;

/**
 * Shown to the admin once, to pass on to the person (in person or on WhatsApp). It is not stored anywhere readable:
 * only its hash is kept, and the person must replace it when they next sign in.
 */
public record TemporaryPasswordResponse(Long userId, String displayName, String email, String temporaryPassword) {
}
