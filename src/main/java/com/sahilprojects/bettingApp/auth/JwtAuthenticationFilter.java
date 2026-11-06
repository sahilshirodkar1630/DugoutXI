package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the Bearer token, then loads the user from the database. The role and the active flag come from the
 * database, not from the token, so deactivating a user or changing a role takes effect on the next request.
 * Not a Spring bean on purpose: SecurityConfig creates it, so it is not registered a second time as a servlet filter.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            try {
                JwtService.ParsedToken token = jwtService.parse(header.substring(PREFIX.length()));
                userRepository.findById(token.userId())
                        .filter(User::isActive)
                        .filter(user -> issuedAfterPasswordChange(token, user))
                        .ifPresent(this::authenticate);
            } catch (JwtException | IllegalArgumentException ignored) {
                // invalid or expired token: leave the request unauthenticated, security returns 401
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * A token from before the latest password change is refused, so a reset or a change signs out every other
     * session. Tokens carry whole seconds, so the change is compared at that precision too: the token handed out
     * with the new password, in the same second, still works.
     */
    private static boolean issuedAfterPasswordChange(JwtService.ParsedToken token, User user) {
        if (user.getPasswordChangedAt() == null) return true;
        if (token.issuedAt() == null) return false;
        return !token.issuedAt().isBefore(user.getPasswordChangedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    private void authenticate(User user) {
        var principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
