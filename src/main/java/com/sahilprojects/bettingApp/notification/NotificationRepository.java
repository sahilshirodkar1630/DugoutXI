package com.sahilprojects.bettingApp.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByDedupeKey(String dedupeKey);

    /** Sender job: due, unsent rows. FOR UPDATE SKIP LOCKED means a second run or instance skips them. */
    @Query(value = "select * from notifications where sent_at is null and scheduled_for <= :now "
            + "order by scheduled_for limit :limit for update skip locked", nativeQuery = true)
    List<Notification> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    /** The user's inbox: only notifications already sent. */
    List<Notification> findByUser_IdAndSentAtIsNotNullOrderBySentAtDesc(Long userId, Pageable pageable);

    long countByUser_IdAndSentAtIsNotNullAndReadAtIsNull(Long userId);

    /** When a start time changes: drop that contest's unsent reminders, then insert new ones. */
    @Modifying
    @Query("delete from Notification n where n.referenceType = :refType and n.referenceId = :refId "
            + "and n.sentAt is null and n.notificationType in :types")
    int deleteUnsent(@Param("refType") ReferenceType refType,
                     @Param("refId") Long refId,
                     @Param("types") Collection<NotificationType> types);
}
