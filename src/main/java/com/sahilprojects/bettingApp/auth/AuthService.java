package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.auth.dto.AuthResponse;
import com.sahilprojects.bettingApp.auth.dto.LoginRequest;
import com.sahilprojects.bettingApp.auth.dto.RegisterRequest;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.ForbiddenException;
import com.sahilprojects.bettingApp.common.UnauthorizedException;
import com.sahilprojects.bettingApp.user.AdminEmails;
import com.sahilprojects.bettingApp.user.Role;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import com.sahilprojects.bettingApp.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AdminEmails adminEmails;
    /** Compared against when the email is unknown, so a wrong email and a wrong password take the same time. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AdminEmails adminEmails) {
        this.adminEmails = adminEmails;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        String name = request.name().trim();

        User user = new User();
        user.setName(name);
        user.setDisplayName(blankToNull(request.displayName()) != null ? request.displayName().trim() : name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPhoneNumber(blankToNull(request.phoneNumber()));
        // The role is never taken from the request. Only the server-side admin list (AdminEmails) makes an admin here.
        user.setRole(adminEmails.contains(email) ? Role.ADMIN : Role.USER);
        userRepository.save(user);

        return respond(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(normalize(request.email()));
        String hash = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordOk = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !passwordOk) {
            throw new UnauthorizedException("Invalid email or password");
        }
        User user = found.get();
        if (!user.isActive()) {
            throw new ForbiddenException("This account is disabled");
        }
        return respond(user);
    }

    /** A fresh token for somebody already known, used after a password change. */
    public AuthResponse respondFor(User user) {
        return respond(user);
    }

    private AuthResponse respond(User user) {
        IssuedToken token = jwtService.issue(user.getId(), user.getRole());
        return new AuthResponse(token.token(), token.expiresAt(), UserResponse.from(user));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
