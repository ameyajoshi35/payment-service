package com.payment.service.service;

import com.payment.service.dto.request.LoginRequest;
import com.payment.service.dto.request.RegisterRequest;
import com.payment.service.dto.response.AuthResponse;
import com.payment.service.exception.PaymentException;
import com.payment.service.model.RefreshToken;
import com.payment.service.model.User;
import com.payment.service.model.Wallet;
import com.payment.service.repository.UserRepository;
import com.payment.service.repository.WalletRepository;
import com.payment.service.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthService authService;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, walletRepository,
                jwtTokenProvider, refreshTokenService, passwordEncoder);
        ReflectionTestUtils.setField(authService, "accessTokenExpiration", 900_000L);
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    void register_success_createsUserAndWallet() {
        RegisterRequest req = new RegisterRequest(
                "new@example.com", "password123", "Alice", "Smith", "+14155551234");

        when(userRepository.existsByEmail(req.email())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(req.phoneNumber())).thenReturn(false);
        when(passwordEncoder.encode(req.password())).thenReturn("hashed-pw");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", USER_ID);
            return u;
        });
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwtTokenProvider.generateAccessToken(eq(USER_ID), eq(req.email())))
                .thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(any()))
                .thenReturn(stubRefreshToken());

        AuthResponse response = authService.register(req);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.user().email()).isEqualTo("new@example.com");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hashed-pw");

        verify(walletRepository).save(any(Wallet.class));
    }

    @Test
    void register_emailAlreadyExists_throwsPaymentException() {
        RegisterRequest req = new RegisterRequest(
                "taken@example.com", "password123", "Bob", "Jones", null);

        when(userRepository.existsByEmail(req.email())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Email already registered");

        verify(userRepository, never()).save(any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void register_phoneAlreadyExists_throwsPaymentException() {
        RegisterRequest req = new RegisterRequest(
                "new@example.com", "password123", "Bob", "Jones", "+14155550000");

        when(userRepository.existsByEmail(req.email())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(req.phoneNumber())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Phone number already registered");
    }

    @Test
    void register_passwordIsHashed_rawPasswordNotStored() {
        RegisterRequest req = new RegisterRequest(
                "new@example.com", "plaintext-password", "Carol", "Brown", null);

        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode("plaintext-password")).thenReturn("$2a$12$...");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", USER_ID);
            return u;
        });
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwtTokenProvider.generateAccessToken(any(), any())).thenReturn("token");
        when(refreshTokenService.createRefreshToken(any())).thenReturn(stubRefreshToken());

        authService.register(req);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash())
                .isEqualTo("$2a$12$...")
                .doesNotContain("plaintext-password");
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returnsTokens() {
        User user = makeUser();
        LoginRequest req = new LoginRequest("user@example.com", "correct-password");

        when(userRepository.findByEmail(req.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "hashed-pw")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(USER_ID, user.getEmail())).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(user)).thenReturn(stubRefreshToken());

        AuthResponse response = authService.login(req);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void login_userNotFound_throwsBadCredentials() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("no@example.com", "pw")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void login_wrongPassword_throwsBadCredentials() {
        User user = makeUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed-pw")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_disabledAccount_throwsPaymentException() {
        User user = makeUser();
        user.setEnabled(false);

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "pw")))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void login_userNotFound_sameErrorAsWrongPassword() {
        // Prevents user enumeration — both cases return BadCredentialsException
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("x@example.com", "pw")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_revokesAllUserRefreshTokens() {
        User user = makeUser();

        authService.logout(user);

        verify(refreshTokenService).revokeAllUserTokens(user);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private User makeUser() {
        User u = new User();
        u.setEmail("user@example.com");
        u.setPasswordHash("hashed-pw");
        u.setFirstName("Test");
        u.setLastName("User");
        ReflectionTestUtils.setField(u, "id", USER_ID);
        return u;
    }

    private RefreshToken stubRefreshToken() {
        User user = makeUser();
        RefreshToken rt = new RefreshToken("refresh-token-value", user, Instant.now().plusSeconds(604800));
        ReflectionTestUtils.setField(rt, "id", UUID.randomUUID());
        return rt;
    }
}
