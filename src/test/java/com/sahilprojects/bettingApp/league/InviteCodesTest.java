package com.sahilprojects.bettingApp.league;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InviteCodesTest {

    @Test
    void codesHaveTheRightLengthAndOnlyUnambiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            String code = InviteCodes.next();
            assertThat(code).hasSize(InviteCodes.LENGTH);
            assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]+");
            assertThat(code).doesNotContain("0", "O", "1", "I", "L");
        }
    }

    @Test
    void aThousandCodesAreAllDifferent() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(InviteCodes.next());
        }
        assertThat(codes).hasSize(1000);
    }
}
