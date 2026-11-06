package com.sahilprojects.bettingApp.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.dto.AuthResponse;
import com.sahilprojects.bettingApp.auth.dto.ForgotPasswordRequest;
import com.sahilprojects.bettingApp.auth.dto.LoginRequest;
import com.sahilprojects.bettingApp.auth.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordService passwordService;

    public AuthController(AuthService authService, PasswordService passwordService) {
        this.authService = authService;
        this.passwordService = passwordService;
    }

    @Operation(summary = "Create an account",
            description = "Signs the new account straight in: the answer carries a token. The role is always USER, unless the email is on the server's ADMIN_EMAILS list.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /** Always 202, whether or not the email has an account. The admins are told when it does. */
    @Operation(summary = "Ask the admins for a password reset",
            description = "There is no email, so this alerts the admins in the app, at most once an hour per account. Always 202, whether or not the email has an account.")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordService.requestReset(request.email());
    }

    @Operation(summary = "Sign in",
            description = "Returns a token to send as `Authorization: Bearer <token>`. If `user.mustChangePassword` is true the person signed in with a temporary password and must change it first.")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
