package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.auth.dto.AuthResponse;
import com.sahilprojects.bettingApp.auth.dto.ForgotPasswordRequest;
import com.sahilprojects.bettingApp.auth.dto.LoginRequest;
import com.sahilprojects.bettingApp.auth.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordService passwordService;

    public AuthController(AuthService authService, PasswordService passwordService) {
        this.authService = authService;
        this.passwordService = passwordService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /** Always 202, whether or not the email has an account. The admins are told when it does. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordService.requestReset(request.email());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
