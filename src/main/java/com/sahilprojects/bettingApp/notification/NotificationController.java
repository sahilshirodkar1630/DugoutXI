package com.sahilprojects.bettingApp.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.notification.dto.NotificationResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** My inbox, newest first. Queued reminders appear only once their time has come. */
    @Operation(summary = "Your notifications",
            description = "Newest first. `limit` defaults to 50.")
    @GetMapping
    public List<NotificationResponse> inbox(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @RequestParam(defaultValue = "50") int limit) {
        return service.inbox(actor, limit);
    }

    /** What the frontend polls for the badge. */
    @Operation(summary = "How many are unread",
            description = "What the app polls for the badge.")
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthenticatedUser actor) {
        return Map.of("unread", service.unreadCount(actor));
    }

    @Operation(summary = "Mark one read")
    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id) {
        return service.markRead(actor, id);
    }

    @Operation(summary = "Mark all read")
    @PostMapping("/read-all")
    public Map<String, Long> markAllRead(@AuthenticationPrincipal AuthenticatedUser actor) {
        return Map.of("marked", service.markAllRead(actor));
    }
}
