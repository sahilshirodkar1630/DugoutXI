package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.auth.dto.AuthResponse;
import com.sahilprojects.bettingApp.auth.dto.ChangePasswordRequest;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.notification.NotificationService;
import com.sahilprojects.bettingApp.notification.NotificationType;
import com.sahilprojects.bettingApp.user.Role;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import com.sahilprojects.bettingApp.user.dto.TemporaryPasswordResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Passwords, without email.
 *
 * The app sends no email, so there is no reset link. Instead somebody who is locked out taps "Forgot password?",
 * which tells the admins; an admin sets a temporary password and passes it on (in person or on WhatsApp); and the
 * person must choose their own the next time they sign in. Every password change, by anyone, signs out every older
 * session (see JwtAuthenticationFilter).
 */
@Service
public class PasswordService {

    /** No 0/O, 1/l/I: a temporary password is read out or typed from a phone screen. */
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int TEMPORARY_LENGTH = 10;
    private static final Duration REQUEST_COOL_DOWN = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthService authService;
    private final AccessGuard accessGuard;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    public PasswordService(UserRepository users, PasswordEncoder encoder, AuthService authService,
                           AccessGuard accessGuard, AuditService auditService,
                           NotificationService notificationService, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.authService = authService;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /** Your own password. Returns a new token, because every older one, this device's included, stops working. */
    @Transactional
    public AuthResponse change(AuthenticatedUser actor, ChangePasswordRequest request) {
        User user = load(actor.id());
        if (!encoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Your current password is not right");
        }
        if (encoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Choose a new password that is different from the current one");
        }
        user.setPasswordHash(encoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(clock.instant());
        auditService.record(AuditEntry.builder()
                .action(AuditAction.USER_PASSWORD_CHANGED)
                .entityType("USER").entityId(user.getId())
                .actorId(actor.id()).actorType(ActorType.USER)
                .build());
        return authService.respondFor(user);
    }

    /**
     * An admin gives somebody a temporary password, which is returned once and never stored readable. The person is
     * signed out everywhere and must choose their own password when they next sign in.
     */
    @Transactional
    public TemporaryPasswordResponse adminReset(AuthenticatedUser actor, Long userId, String reason) {
        accessGuard.requireAdminWithReason(actor, reason);
        if (actor.id().equals(userId)) {
            throw new BadRequestException("Change your own password from your profile instead");
        }
        User user = load(userId);
        String temporary = temporaryPassword();
        user.setPasswordHash(encoder.encode(temporary));
        user.setMustChangePassword(true);
        user.setPasswordChangedAt(clock.instant());
        user.setPasswordResetRequestedAt(null);
        auditService.record(AuditEntry.builder()
                .action(AuditAction.USER_PASSWORD_RESET)
                .entityType("USER").entityId(user.getId())
                .actorId(actor.id()).actorType(ActorType.ADMIN).reason(reason)
                .after(Map.<String, Object>of("mustChangePassword", true))
                .build());
        return new TemporaryPasswordResponse(user.getId(), user.getDisplayName(), user.getEmail(), temporary);
    }

    /**
     * "Forgot password?" from the sign-in screen. Tells every admin, at most once an hour per account. The answer to
     * the caller is the same whether or not the email has an account, so the form cannot be used to find out who
     * is registered.
     */
    @Transactional
    public void requestReset(String email) {
        User user = users.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
        if (user == null || !user.isActive()) {
            return;
        }
        Instant now = clock.instant();
        Instant last = user.getPasswordResetRequestedAt();
        if (last != null && last.isAfter(now.minus(REQUEST_COOL_DOWN))) {
            return;
        }
        user.setPasswordResetRequestedAt(now);
        var adminIds = users.findByRoleAndActiveTrue(Role.ADMIN).stream()
                .map(User::getId)
                .filter(id -> !id.equals(user.getId()))
                .toList();
        notificationService.notifyAll(adminIds, NotificationType.PASSWORD_RESET_REQUESTED, null, null,
                "Password reset requested",
                user.getDisplayName() + " (" + user.getEmail() + ") cannot sign in and asked for a new password. "
                        + "Reset it from Admin → Accounts and send them the temporary password.");
    }

    private User load(Long userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    static String temporaryPassword() {
        StringBuilder out = new StringBuilder(TEMPORARY_LENGTH);
        for (int i = 0; i < TEMPORARY_LENGTH; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
