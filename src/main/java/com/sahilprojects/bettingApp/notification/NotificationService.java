package com.sahilprojects.bettingApp.notification;

import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.notification.dto.NotificationResponse;
import com.sahilprojects.bettingApp.user.User;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * In-app notifications. There is no email or push in this version: a notification is a row the user sees in their
 * inbox, and the frontend polls the unread count.
 *
 * Two kinds of row land in the same table. An immediate one is written with sentAt already set, so it shows up at
 * once. A reminder is written with a future scheduledFor and no sentAt, and the sender job marks it sent when its
 * time comes. That means a reminder can still be withdrawn: if a contest's start time moves or the contest is
 * cancelled, its unsent reminders are deleted and, where it still makes sense, replaced.
 *
 * Every row carries a dedupe key, unique in the database. Writing the same notification twice is therefore harmless:
 * the second attempt is dropped rather than doubling up in someone's inbox.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Reminders a contest schedules ahead of its start, and which a change to that start time must rebuild. */
    private static final List<NotificationType> CONTEST_REMINDERS =
            List.of(NotificationType.CONTEST_REMINDER, NotificationType.PREDICTION_REMINDER);

    private static final Duration CONTEST_REMINDER_LEAD = Duration.ofHours(1);
    private static final Duration PREDICTION_REMINDER_LEAD = Duration.ofMinutes(15);

    private final NotificationRepository notifications;
    private final EntityManager entityManager;
    private final Clock clock;

    /** Only ever used to keep an untagged dedupe key unique. */
    private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();

    public NotificationService(NotificationRepository notifications, EntityManager entityManager, Clock clock) {
        this.notifications = notifications;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ---------- writing ----------

    /** Sends one notification now. Runs in the caller's transaction, so it is rolled back with whatever caused it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyNow(Long userId, NotificationType type, ReferenceType refType, Long refId,
                          String title, String message) {
        Instant now = clock.instant();
        save(userId, type, refType, refId, title, message, now, now, dedupeKey(type, refType, refId, userId, null));
    }

    /**
     * Sends one notification now, but only the first time for this tag. Use it where a repeat would be noise:
     * one "result is in" per person per contest, however many times the code path runs.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyOnce(Long userId, NotificationType type, ReferenceType refType, Long refId,
                           String title, String message, String tag) {
        Instant now = clock.instant();
        save(userId, type, refType, refId, title, message, now, now, dedupeKey(type, refType, refId, userId, tag));
    }

    /** notifyOnce for a group. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyAllOnce(Collection<Long> userIds, NotificationType type, ReferenceType refType, Long refId,
                              String title, String message, String tag) {
        for (Long userId : userIds) {
            notifyOnce(userId, type, refType, refId, title, message, tag);
        }
    }

    /** Sends the same notification to several people at once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyAll(Collection<Long> userIds, NotificationType type, ReferenceType refType, Long refId,
                          String title, String message) {
        for (Long userId : userIds) {
            notifyNow(userId, type, refType, refId, title, message);
        }
    }

    /** Queues a notification for later. The sender job delivers it once scheduledFor has passed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(Long userId, NotificationType type, ReferenceType refType, Long refId,
                         String title, String message, Instant scheduledFor) {
        save(userId, type, refType, refId, title, message, scheduledFor, null,
                dedupeKey(type, refType, refId, userId, String.valueOf(scheduledFor.getEpochSecond())));
    }

    /**
     * Replaces a contest's unsent reminders: one an hour before the start and one fifteen minutes before.
     * Called when a contest is created, when its start time changes, and when its participants change.
     * A reminder whose moment has already passed is simply not created.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rebuildContestReminders(Long contestId, String contestLabel, Instant startTime,
                                        Collection<Long> userIds) {
        notifications.deleteUnsent(ReferenceType.CONTEST, contestId, CONTEST_REMINDERS);
        if (startTime == null || userIds.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Instant hourBefore = startTime.minus(CONTEST_REMINDER_LEAD);
        Instant quarterBefore = startTime.minus(PREDICTION_REMINDER_LEAD);

        for (Long userId : userIds) {
            if (hourBefore.isAfter(now)) {
                schedule(userId, NotificationType.CONTEST_REMINDER, ReferenceType.CONTEST, contestId,
                        contestLabel + " starts in an hour",
                        "Make your pick before the contest starts.", hourBefore);
            }
            if (quarterBefore.isAfter(now)) {
                schedule(userId, NotificationType.PREDICTION_REMINDER, ReferenceType.CONTEST, contestId,
                        "Last call for " + contestLabel,
                        "Predictions lock in 15 minutes.", quarterBefore);
            }
        }
    }

    /** Drops a contest's queued reminders, for a contest that was cancelled or has started. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelContestReminders(Long contestId) {
        return notifications.deleteUnsent(ReferenceType.CONTEST, contestId, CONTEST_REMINDERS);
    }

    // ---------- the sender job ----------

    /**
     * Marks due reminders as sent, which is what puts them in someone's inbox.
     * Rows another instance has already picked up are skipped, so running this twice at once is safe.
     */
    @Transactional
    public int sendDue(int limit) {
        Instant now = clock.instant();
        List<Notification> due = notifications.lockDue(now, limit);
        for (Notification n : due) {
            n.setSentAt(now);
        }
        if (!due.isEmpty()) {
            log.debug("Sent {} notification(s)", due.size());
        }
        return due.size();
    }

    // ---------- the inbox ----------

    @Transactional(readOnly = true)
    public List<NotificationResponse> inbox(AuthenticatedUser actor, int limit) {
        return notifications
                .findByUser_IdAndSentAtIsNotNullOrderBySentAtDesc(actor.id(), PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(NotificationService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(AuthenticatedUser actor) {
        return notifications.countByUser_IdAndSentAtIsNotNullAndReadAtIsNull(actor.id());
    }

    @Transactional
    public NotificationResponse markRead(AuthenticatedUser actor, Long id) {
        Notification n = notifications.findById(id)
                .filter(row -> row.getUser().getId().equals(actor.id()))
                .filter(row -> row.getSentAt() != null)
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        if (n.getReadAt() == null) {
            n.setReadAt(clock.instant());
        }
        return toResponse(n);
    }

    @Transactional
    public long markAllRead(AuthenticatedUser actor) {
        Instant now = clock.instant();
        long changed = 0;
        for (Notification n : notifications.findByUser_IdAndSentAtIsNotNullOrderBySentAtDesc(
                actor.id(), PageRequest.of(0, 500))) {
            if (n.getReadAt() == null) {
                n.setReadAt(now);
                changed++;
            }
        }
        return changed;
    }

    // ---------- helpers ----------

    private void save(Long userId, NotificationType type, ReferenceType refType, Long refId,
                      String title, String message, Instant scheduledFor, Instant sentAt, String dedupeKey) {
        if (notifications.existsByDedupeKey(dedupeKey)) {
            return;                                 // already queued or already delivered
        }
        Notification n = new Notification();
        n.setUser(entityManager.getReference(User.class, userId));
        n.setNotificationType(type);
        n.setReferenceType(refType);
        n.setReferenceId(refType == null ? null : refId);
        n.setTitle(title);
        n.setMessage(message);
        n.setScheduledFor(scheduledFor);
        n.setSentAt(sentAt);
        n.setDedupeKey(dedupeKey);
        try {
            notifications.save(n);
        } catch (DataIntegrityViolationException e) {
            // Two requests raced on the same key. The other one won, and that is the right outcome.
            log.debug("Duplicate notification {} ignored", dedupeKey);
        }
    }

    /**
     * Unique per user, per notification, per thing it is about. The suffix separates reminders for different
     * start times, so moving a contest schedules fresh reminders instead of colliding with the old keys.
     */
    private String dedupeKey(NotificationType type, ReferenceType refType, Long refId, Long userId, String suffix) {
        StringBuilder key = new StringBuilder()
                .append(type).append(':')
                .append(refType == null ? "-" : refType).append(':')
                .append(refId == null ? "-" : refId).append(':')
                .append(userId);
        if (suffix != null) {
            key.append(':').append(suffix);
        } else {
            // No tag means "deliver every time": a second start-time change is news again, so the key is made
            // unique rather than deduped. The counter keeps it unique even under a clock that does not move.
            key.append(':').append(clock.instant().toEpochMilli()).append('-').append(sequence.incrementAndGet());
        }
        return key.toString();
    }

    private static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getNotificationType().name(),
                n.getReferenceType() == null ? null : n.getReferenceType().name(), n.getReferenceId(),
                n.getTitle(), n.getMessage(), n.getSentAt(), n.getReadAt());
    }
}
