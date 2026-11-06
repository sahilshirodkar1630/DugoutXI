package com.sahilprojects.bettingApp.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import com.sahilprojects.bettingApp.auth.PasswordService;
import com.sahilprojects.bettingApp.auth.dto.AuthResponse;
import com.sahilprojects.bettingApp.auth.dto.ChangePasswordRequest;
import com.sahilprojects.bettingApp.user.dto.UpdateProfileRequest;
import com.sahilprojects.bettingApp.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Me")
@RestController
@RequestMapping("/api/me")
public class UserController {

    private final UserService userService;
    private final PasswordService passwordService;

    public UserController(UserService userService, PasswordService passwordService) {
        this.userService = userService;
        this.passwordService = passwordService;
    }

    @Operation(summary = "Your profile",
            description = "Also the cheapest way to check that a stored token still works.")
    @GetMapping
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser actor) {
        return userService.get(actor.id());
    }

    /** Returns a new token: every older session, this one included, is signed out. */
    @Operation(summary = "Change your password",
            description = "Needs the current password. Returns a new token: every other session, and the old token, stop working.")
    @PostMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal AuthenticatedUser actor,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        return passwordService.change(actor, request);
    }

    @Operation(summary = "Update your profile",
            description = "Only the fields sent are changed. Role and active flag cannot be changed here.")
    @PatchMapping
    public UserResponse updateMe(@AuthenticationPrincipal AuthenticatedUser actor,
                                 @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(actor.id(), request);
    }
}
