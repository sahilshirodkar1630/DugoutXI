package com.sahilprojects.bettingApp.participation;

import com.sahilprojects.bettingApp.common.ConflictException;

import java.util.Map;
import java.util.Set;

/**
 * The one table of allowed status moves, shared by league members and standalone contest participants.
 * Anything not listed is refused. Pure logic, no database.
 *
 * <pre>
 * PENDING_APPROVAL -> ACCEPTED_UNPAID | CONFIRMED | REJECTED | CANCELLED
 * ACCEPTED_UNPAID  -> CONFIRMED | REMOVED | CANCELLED
 * CONFIRMED        -> ACCEPTED_UNPAID | REMOVED          (only by a reversal; a confirmed user cannot withdraw)
 * REJECTED         -> PENDING_APPROVAL                   (asks again)
 * CANCELLED        -> PENDING_APPROVAL                   (asks again)
 * REMOVED          -> nothing                            (final in V1)
 * </pre>
 */
public final class ParticipationRules {

    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String ACCEPTED_UNPAID = "ACCEPTED_UNPAID";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REJECTED = "REJECTED";
    public static final String REMOVED = "REMOVED";
    public static final String CANCELLED = "CANCELLED";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            PENDING_APPROVAL, Set.of(ACCEPTED_UNPAID, CONFIRMED, REJECTED, CANCELLED),
            ACCEPTED_UNPAID, Set.of(CONFIRMED, REMOVED, CANCELLED),
            CONFIRMED, Set.of(ACCEPTED_UNPAID, REMOVED),
            REJECTED, Set.of(PENDING_APPROVAL),
            CANCELLED, Set.of(PENDING_APPROVAL),
            REMOVED, Set.of());

    private ParticipationRules() {
    }

    public static boolean isAllowed(String from, String to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireMove(String from, String to) {
        if (!isAllowed(from, to)) {
            throw new ConflictException("This cannot move from " + from + " to " + to);
        }
    }
}
