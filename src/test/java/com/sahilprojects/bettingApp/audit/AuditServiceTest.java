package com.sahilprojects.bettingApp.audit;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final AuditService service = new AuditService(repository, em);

    @Test
    void adminEntryWithoutReasonIsRefused() {
        var entry = AuditEntry.builder().action(AuditAction.ADMIN_OVERRIDE).entityType("LEAGUE").entityId(1L)
                .actorId(9L).actorType(ActorType.ADMIN).reason("  ").build();

        assertThatThrownBy(() -> service.record(entry)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void userEntryWithoutActorIsRefused() {
        var entry = AuditEntry.builder().action(AuditAction.START_TIME_CHANGED).entityType("CONTEST").entityId(1L)
                .actorType(ActorType.USER).build();

        assertThatThrownBy(() -> service.record(entry)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemEntryNeedsNoActor() {
        var entry = AuditEntry.builder().action(AuditAction.CONTEST_CANCELLED).entityType("CONTEST").entityId(3L)
                .actorType(ActorType.SYSTEM).reason("Fewer than 3 confirmed").build();

        service.record(entry);

        verify(repository).save(any(AuditLog.class));
    }

    @Test
    void visibilityFollowsTheAction() {
        var visible = AuditEntry.builder().action(AuditAction.START_TIME_CHANGED).entityType("CONTEST").entityId(1L)
                .actorType(ActorType.SYSTEM).after(Map.of("startTime", "x")).build();
        var hidden = AuditEntry.builder().action(AuditAction.MEMBER_REMOVED).entityType("LEAGUE_MEMBER").entityId(2L)
                .actorType(ActorType.SYSTEM).build();

        service.record(visible);
        service.record(hidden);

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).isVisibleToMembers()).isTrue();
        assertThat(saved.getAllValues().get(1).isVisibleToMembers()).isFalse();
    }
}
