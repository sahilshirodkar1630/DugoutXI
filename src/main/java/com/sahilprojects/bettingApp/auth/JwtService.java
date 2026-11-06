package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.user.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String DEV_SECRET_PREFIX = "dev-only-secret";

    private final SecretKey key;
    private final Duration expiration;
    private final Clock clock;

    /** For unit tests that have no Spring context. */
    public JwtService(JwtProperties props) {
        this(props, Clock.systemUTC());
    }

    @Autowired
    public JwtService(JwtProperties props, Clock clock) {
        this.clock = clock;
        byte[] bytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes long");
        }
        if (props.secret().startsWith(DEV_SECRET_PREFIX)) {
            log.warn("Using the development JWT secret. Set JWT_SECRET before deploying.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expiration = props.expiration();
    }

    public IssuedToken issue(Long userId, Role role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(expiration);
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role.name())          // for the client's convenience only; the server reads the role from the database
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Returns the user id. Throws a JwtException when the token is invalid, tampered with or expired. */
    public long parseUserId(String token) {
        return parse(token).userId();
    }

    /** The user id and when the token was issued. Throws a JwtException when the token is not valid. */
    public ParsedToken parse(String token) {
        var claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                .parseSignedClaims(token).getPayload();
        Date issuedAt = claims.getIssuedAt();
        return new ParsedToken(Long.parseLong(claims.getSubject()), issuedAt == null ? null : issuedAt.toInstant());
    }

    public record ParsedToken(long userId, Instant issuedAt) {
    }
}
