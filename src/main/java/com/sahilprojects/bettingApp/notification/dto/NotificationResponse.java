package com.sahilprojects.bettingApp.notification.dto;

import java.time.Instant;

/** One row in the inbox. referenceType and referenceId tell the frontend where to link to. */
public record NotificationResponse(
        long id,
        String type,
        String referenceType,
        Long referenceId,
        String title,
        String message,
        Instant sentAt,
        Instant readAt) {

    public boolean read() {
        return readAt != null;
    }
}
