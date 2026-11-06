package com.sahilprojects.bettingApp.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.auth.PasswordService;
import com.sahilprojects.bettingApp.user.dto.AdminResetPasswordRequest;
import com.sahilprojects.bettingApp.user.dto.TemporaryPasswordResponse;
import com.sahilprojects.bettingApp.user.dto.AdminSetActiveRequest;
import com.sahilprojects.bettingApp.user.dto.AdminSetRoleRequest;
import com.sahilprojects.bettingApp.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Reached only by admins (SecurityConfig), and every change is audited with its reason. */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserService userService;
    private final PasswordService passwordService;

    public AdminUserController(UserService userService, PasswordService passwordService) {
        this.userService = userService;
        this.passwordService = passwordService;
    }

    /** The user list, for switching an account off or making somebody an admin. */
    @Operation(summary = "List accounts",
            description = "Newest first, optionally narrowed by `search` across name, display name and email. At most 200.")
    @GetMapping
    public java.util.List<com.sahilprojects.bettingApp.user.dto.AdminUserResponse> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "50") int limit) {
        return userService.list(actor, search, limit);
    }

    @Operation(summary = "Switch an account on or off",
            description = "Takes effect on that person's very next request. Needs a reason.")
    @PutMapping("/{id}/active")
    public UserResponse setActive(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                  @Valid @RequestBody AdminSetActiveRequest request) {
        return userService.setActive(actor, id, request.active(), request.reason());
    }

    /** A temporary password for somebody locked out. Shown once; they must replace it when they sign in. */
    @Operation(summary = "Give someone a temporary password",
            description = "Shown once in the answer and never stored readable. The person is signed out everywhere and must choose their own password at the next sign-in. Needs a reason; not for your own account.")
    @PostMapping("/{id}/password-reset")
    public TemporaryPasswordResponse resetPassword(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                                   @Valid @RequestBody AdminResetPasswordRequest request) {
        return passwordService.adminReset(actor, id, request.reason());
    }

    @Operation(summary = "Make someone an admin or a player",
            description = "Needs a reason. You cannot change your own role.")
    @PutMapping("/{id}/role")
    public UserResponse setRole(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long id,
                                @Valid @RequestBody AdminSetRoleRequest request) {
        return userService.setRole(actor, id, request.role(), request.reason());
    }
}
