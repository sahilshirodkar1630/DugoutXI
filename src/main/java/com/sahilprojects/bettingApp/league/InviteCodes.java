package com.sahilprojects.bettingApp.league;

import java.security.SecureRandom;

/** Random invitation codes. No 0, O, 1, I or L, so a code read aloud or typed from a screenshot is not misread. */
final class InviteCodes {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodes() {
    }

    static String next() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
