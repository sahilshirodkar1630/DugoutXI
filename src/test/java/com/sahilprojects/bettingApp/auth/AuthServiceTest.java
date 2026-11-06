package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.auth.dto.AuthResponse;
import com.sahilprojects.bettingApp.auth.dto.LoginRequest;
import com.sahilprojects.bettingApp.auth.dto.RegisterRequest;
import com.sahilprojects.bettingApp.common.ConflictException;
import com.sahilprojects.bettingApp.common.ForbiddenException;
import com.sahilprojects.bettingApp.common.UnauthorizedException;
import com.sahilprojects.bettingApp.user.Role;
import com.sahilprojects.bettingApp.user.User;
import com.sahilprojects.bettingApp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtService jwt = new JwtService(new JwtProperties(
            "a-test-secret-that-is-long-enough-for-hs256-0123456789", Duration.ofHours(1)));
    private final AuthService service = new AuthService(users, encoder, jwt, com.sahilprojects.bettingApp.user.AdminEmails.of("boss@example.com"));

    private User storedUser(String email, String password, boolean active) {
        User u = new User();
        u.setId(5L);
        u.setName("Stored");
        u.setDisplayName("Stored");
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setActive(active);
        return u;
    }

    @Test
    void registerLowercasesEmailHashesPasswordAndDefaultsDisplayName() {
        when(users.existsByEmailIgnoreCase("rahul@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });

        AuthResponse response = service.register(
                new RegisterRequest("  Rahul Sharma ", null, "  Rahul@Example.COM ", "password123", null));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User user = saved.getValue();
        assertThat(user.getEmail()).isEqualTo("rahul@example.com");
        assertThat(user.getName()).isEqualTo("Rahul Sharma");
        assertThat(user.getDisplayName()).isEqualTo("Rahul Sharma");
        assertThat(user.getPasswordHash()).isNotEqualTo("password123");
        assertThat(encoder.matches("password123", user.getPasswordHash())).isTrue();
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(jwt.parseUserId(response.token())).isEqualTo(7L);
    }

    @Test
    void anEmailOnTheServerAdminListRegistersAsAnAdmin() {
        when(users.existsByEmailIgnoreCase("boss@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(8L);
            return u;
        });

        service.register(new RegisterRequest("Boss", null, "Boss@Example.com", "password123", null));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void registerWithExistingEmailIsAConflict() {
        when(users.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("A", null, "taken@example.com", "password123", null)))
                .isInstanceOf(ConflictException.class);
        verify(users, never()).save(any());
    }

    @Test
    void loginSucceedsWithRightPassword() {
        when(users.findByEmailIgnoreCase("a@example.com"))
                .thenReturn(Optional.of(storedUser("a@example.com", "password123", true)));

        AuthResponse response = service.login(new LoginRequest("A@Example.com", "password123"));

        assertThat(jwt.parseUserId(response.token())).isEqualTo(5L);
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameError() {
        when(users.findByEmailIgnoreCase("a@example.com"))
                .thenReturn(Optional.of(storedUser("a@example.com", "password123", true)));
        when(users.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("a@example.com", "wrong-password")))
                .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid email or password");
        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "whatever123")))
                .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid email or password");
    }

    @Test
    void disabledAccountCannotLogIn() {
        when(users.findByEmailIgnoreCase("off@example.com"))
                .thenReturn(Optional.of(storedUser("off@example.com", "password123", false)));

        assertThatThrownBy(() -> service.login(new LoginRequest("off@example.com", "password123")))
                .isInstanceOf(ForbiddenException.class);
    }
}
