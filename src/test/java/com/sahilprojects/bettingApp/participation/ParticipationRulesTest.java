package com.sahilprojects.bettingApp.participation;

import com.sahilprojects.bettingApp.common.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParticipationRulesTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING_APPROVAL, ACCEPTED_UNPAID",
            "PENDING_APPROVAL, CONFIRMED",
            "PENDING_APPROVAL, REJECTED",
            "PENDING_APPROVAL, CANCELLED",
            "ACCEPTED_UNPAID, CONFIRMED",
            "ACCEPTED_UNPAID, REMOVED",
            "ACCEPTED_UNPAID, CANCELLED",
            "CONFIRMED, ACCEPTED_UNPAID",
            "CONFIRMED, REMOVED",
            "REJECTED, PENDING_APPROVAL",
            "CANCELLED, PENDING_APPROVAL"})
    void allowedMoves(String from, String to) {
        assertThat(ParticipationRules.isAllowed(from, to)).isTrue();
        ParticipationRules.requireMove(from, to);
    }

    @ParameterizedTest
    @CsvSource({
            "CONFIRMED, CANCELLED",            // a confirmed user cannot withdraw
            "CONFIRMED, REJECTED",
            "CONFIRMED, PENDING_APPROVAL",
            "REMOVED, PENDING_APPROVAL",       // removed is final in V1
            "REMOVED, CONFIRMED",
            "REJECTED, CONFIRMED",
            "CANCELLED, CONFIRMED",
            "PENDING_APPROVAL, REMOVED",
            "ACCEPTED_UNPAID, REJECTED",
            "ACCEPTED_UNPAID, PENDING_APPROVAL"})
    void refusedMoves(String from, String to) {
        assertThat(ParticipationRules.isAllowed(from, to)).isFalse();
        assertThatThrownBy(() -> ParticipationRules.requireMove(from, to)).isInstanceOf(ConflictException.class);
    }

    @Test
    void movingToTheSameStatusIsRefused() {
        for (String s : new String[]{"PENDING_APPROVAL", "ACCEPTED_UNPAID", "CONFIRMED", "REJECTED", "CANCELLED", "REMOVED"}) {
            assertThat(ParticipationRules.isAllowed(s, s)).as(s).isFalse();
        }
    }

    @Test
    void unknownStatusIsRefused() {
        assertThat(ParticipationRules.isAllowed("NOPE", "CONFIRMED")).isFalse();
    }
}
