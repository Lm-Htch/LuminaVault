package com.lumi.vault.business.auth.controller;

import com.lumi.vault.business.auth.dto.LoginRequest;
import com.lumi.vault.business.auth.dto.RefreshRequest;
import com.lumi.vault.business.auth.dto.RegisterRequest;
import com.lumi.vault.business.auth.dto.TokenResponse;
import com.lumi.vault.business.auth.dto.UserResponse;
import com.lumi.vault.business.auth.security.AuthenticatedUser;
import com.lumi.vault.business.auth.service.AuthService;
import com.lumi.vault.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@AuthenticationPrincipal AuthenticatedUser principal,
                               @RequestBody(required = false) RefreshRequest request) {
        authService.logout(principal, request == null ? null : request.refreshToken());
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Result.ok(authService.currentUser(principal.id()));
    }
}
