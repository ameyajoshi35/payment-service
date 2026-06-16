package com.payment.service.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    // Base64("secure-payment-service-jwt-secret-key-256-bits-long") — 50 chars = 400 bits
    private static final String SECRET = "c2VjdXJlLXBheW1lbnQtc2VydmljZS1qd3Qtc2VjcmV0LWtleS0yNTYtYml0cy1sb25n";
    private static final long EXPIRATION_MS = 900_000L; // 15 min

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET, EXPIRATION_MS);
    }

    @Test
    void generateAccessToken_returnsNonNullToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.generateAccessToken(userId, "user@example.com");

        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void generateAccessToken_tokenHasThreeParts() {
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "user@example.com");

        // JWT format: header.payload.signature
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void getUserIdFromToken_returnsCorrectUserId() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.generateAccessToken(userId, "user@example.com");

        UUID extracted = jwtTokenProvider.getUserIdFromToken(token);

        assertThat(extracted).isEqualTo(userId);
    }

    @Test
    void validateAndGetClaims_returnsCorrectEmail() {
        String email = "test@example.com";
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), email);

        Claims claims = jwtTokenProvider.validateAndGetClaims(token);

        assertThat(claims.get("email", String.class)).isEqualTo(email);
    }

    @Test
    void isTokenValid_validToken_returnsTrue() {
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "user@example.com");

        assertThat(jwtTokenProvider.isTokenValid(token)).isTrue();
    }

    @Test
    void isTokenValid_tamperedToken_returnsFalse() {
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "user@example.com");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertThat(jwtTokenProvider.isTokenValid(tampered)).isFalse();
    }

    @Test
    void isTokenValid_expiredToken_returnsFalse() {
        JwtTokenProvider shortLivedProvider = new JwtTokenProvider(SECRET, 1L); // 1ms expiry
        String token = shortLivedProvider.generateAccessToken(UUID.randomUUID(), "user@example.com");

        // Token expires almost immediately
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        assertThat(shortLivedProvider.isTokenValid(token)).isFalse();
    }

    @Test
    void isTokenValid_emptyString_returnsFalse() {
        assertThat(jwtTokenProvider.isTokenValid("")).isFalse();
    }

    @Test
    void isTokenValid_randomString_returnsFalse() {
        assertThat(jwtTokenProvider.isTokenValid("not.a.jwt")).isFalse();
    }

    @Test
    void differentUserIds_produceDistinctTokens() {
        String token1 = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "a@example.com");
        String token2 = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "b@example.com");

        assertThat(token1).isNotEqualTo(token2);
    }
}
