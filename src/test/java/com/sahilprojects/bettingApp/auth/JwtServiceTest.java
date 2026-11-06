package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.user.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-for-hs256-0123456789";

    private static JwtService service(String secret, Duration expiration) {
        return new JwtService(new JwtProperties(secret, expiration));
    }

    @Test
    void tokenRoundTripsTheUserId() {
        JwtService jwt = service(SECRET, Duration.ofHours(1));
        IssuedToken token = jwt.issue(42L, Role.USER);

        assertThat(jwt.parseUserId(token.token())).isEqualTo(42L);
        assertThat(token.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService jwt = service(SECRET, Duration.ofSeconds(-5));
        String token = jwt.issue(1L, Role.USER).token();

        assertThatThrownBy(() -> jwt.parseUserId(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = service(SECRET, Duration.ofHours(1)).issue(1L, Role.ADMIN).token();
        JwtService other = service("a-different-secret-that-is-also-long-enough-987654321", Duration.ofHours(1));

        assertThatThrownBy(() -> other.parseUserId(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageTokenIsRejected() {
        JwtService jwt = service(SECRET, Duration.ofHours(1));
        assertThatThrownBy(() -> jwt.parseUserId("not.a.token")).isInstanceOf(JwtException.class);
    }

    @Test
    void secretShorterThan32BytesIsRefusedAtStartup() {
        assertThatThrownBy(() -> service("too-short", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
