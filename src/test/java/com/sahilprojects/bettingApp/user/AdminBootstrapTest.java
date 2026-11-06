package com.sahilprojects.bettingApp.user;

import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The first admins come from server configuration (app.admin-emails), never from a request. */
class AdminBootstrapTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

    AdminBootstrapTest() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void theListIsTrimmedAndCaseInsensitive() {
        AdminEmails list = new AdminEmails(" Boss@Example.com , ,second@example.com");
        assertThat(list.contains("boss@example.com")).isTrue();
        assertThat(list.contains("  SECOND@example.COM ")).isTrue();
        assertThat(list.contains("someone@example.com")).isFalse();
        assertThat(new AdminEmails("").all()).isEmpty();
    }

    @Test
    void listedAccountsAreMadeAdminsOnceAndAudited() {
        User boss = user(1L, "boss@example.com", Role.USER);
        User already = user(2L, "already@example.com", Role.ADMIN);
        when(users.findByEmailIgnoreCase("boss@example.com")).thenReturn(Optional.of(boss));
        when(users.findByEmailIgnoreCase("already@example.com")).thenReturn(Optional.of(already));
        when(users.findByEmailIgnoreCase("not-registered@example.com")).thenReturn(Optional.empty());

        int promoted = new AdminBootstrap(
                AdminEmails.of("boss@example.com", "already@example.com", "not-registered@example.com"),
                users, audit, tx).promoteListed();

        assertThat(promoted).isEqualTo(1);
        assertThat(boss.getRole()).isEqualTo(Role.ADMIN);
        verify(audit, times(1)).record(any(AuditEntry.class));
    }

    private static User user(long id, String email, Role role) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setRole(role);
        return u;
    }
}
