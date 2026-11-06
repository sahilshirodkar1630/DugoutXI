package com.sahilprojects.bettingApp.user;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.auth.AccessGuard;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.NotFoundException;
import com.sahilprojects.bettingApp.user.dto.AdminUserResponse;
import com.sahilprojects.bettingApp.user.dto.UpdateProfileRequest;
import com.sahilprojects.bettingApp.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final AccessGuard accessGuard;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, AccessGuard accessGuard, AuditService auditService) {
        this.userRepository = userRepository;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long userId) {
        return UserResponse.from(load(userId));
    }

    /**
     * The admin user list, newest first, optionally narrowed by a search across name, display name and email.
     * Capped so one careless request cannot pull the whole table into memory.
     */
    @Transactional(readOnly = true)
    public java.util.List<AdminUserResponse> list(AuthenticatedUser actor, String search, int limit) {
        accessGuard.requireAdmin(actor);
        String term = search == null || search.isBlank() ? null : search.trim();
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        return (term == null ? userRepository.findAllByOrderByIdDesc(page) : userRepository.search(term, page))
                .stream()
                .map(AdminUserResponse::from)
                .toList();
    }

    /** Updates only the fields that were sent. Role and active flag cannot change here. */
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = load(userId);
        if (request.name() != null) user.setName(requireNotBlank(request.name(), "name"));
        if (request.displayName() != null) user.setDisplayName(requireNotBlank(request.displayName(), "displayName"));
        if (request.phoneNumber() != null) user.setPhoneNumber(blankToNull(request.phoneNumber()));
        if (request.photoUrl() != null) user.setPhotoUrl(blankToNull(request.photoUrl()));
        if (request.upiId() != null) user.setUpiId(blankToNull(request.upiId()));
        return UserResponse.from(user);
    }

    /** Admin only, with a reason. The user's existing tokens stop working on their next request. */
    @Transactional
    public UserResponse setActive(AuthenticatedUser actor, Long userId, boolean active, String reason) {
        accessGuard.requireAdminWithReason(actor, reason);
        rejectSelfChange(actor, userId);
        User user = load(userId);
        boolean before = user.isActive();
        if (before == active) {
            return UserResponse.from(user);
        }
        user.setActive(active);
        auditService.record(AuditEntry.builder()
                .action(active ? AuditAction.USER_ACTIVATED : AuditAction.USER_DEACTIVATED)
                .entityType("USER").entityId(user.getId())
                .actorId(actor.id()).actorType(ActorType.ADMIN).reason(reason)
                .before(Map.<String, Object>of("active", before))
                .after(Map.<String, Object>of("active", active))
                .build());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse setRole(AuthenticatedUser actor, Long userId, Role role, String reason) {
        accessGuard.requireAdminWithReason(actor, reason);
        rejectSelfChange(actor, userId);
        User user = load(userId);
        Role before = user.getRole();
        if (before == role) {
            return UserResponse.from(user);
        }
        user.setRole(role);
        auditService.record(AuditEntry.builder()
                .action(AuditAction.USER_ROLE_CHANGED)
                .entityType("USER").entityId(user.getId())
                .actorId(actor.id()).actorType(ActorType.ADMIN).reason(reason)
                .before(Map.<String, Object>of("role", before.name()))
                .after(Map.<String, Object>of("role", role.name()))
                .build());
        return UserResponse.from(user);
    }

    private User load(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private static void rejectSelfChange(AuthenticatedUser actor, Long userId) {
        if (actor.id().equals(userId)) {
            throw new BadRequestException("You cannot change your own access");
        }
    }

    private static String requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new BadRequestException(field + " cannot be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value.trim();
    }
}
