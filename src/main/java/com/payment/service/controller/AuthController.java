package com.payment.service.controller;

import com.payment.service.dto.request.LoginRequest;
import com.payment.service.dto.request.RefreshTokenRequest;
import com.payment.service.dto.request.RegisterRequest;
import com.payment.service.dto.response.ApiResponse;
import com.payment.service.dto.response.AuthResponse;
import com.payment.service.exception.ResourceNotFoundException;
import com.payment.service.model.User;
import com.payment.service.repository.UserRepository;
import com.payment.service.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        log.info("Registration attempt for email={} ip={}", request.email(), httpRequest.getRemoteAddr());

        AuthResponse response = authService.register(request);

        log.info("Registration successful userId={} email={}", response.user().id(), response.user().email());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created successfully", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        log.info("Login attempt email={} ip={}", request.email(), httpRequest.getRemoteAddr());

        AuthResponse response = authService.login(request);

        log.info("Login successful userId={}", response.user().id());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {

        log.debug("Refresh token rotation requested");

        AuthResponse response = authService.refreshToken(request.refreshToken());

        log.info("Token refreshed for userId={}", response.user().id());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = UUID.fromString(principal.getUsername());
        log.info("Logout requested userId={}", userId);

        User user = resolveUser(principal);
        authService.logout(user);

        log.info("Logout successful userId={}", userId);
        return ResponseEntity.ok(ApiResponse.ok("Logged out successfully", null));
    }

    private User resolveUser(UserDetails principal) {
        return userRepository.findById(UUID.fromString(principal.getUsername()))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
