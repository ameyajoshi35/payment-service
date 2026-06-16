package com.payment.service.service;

import com.payment.service.exception.ResourceNotFoundException;
import com.payment.service.model.RefreshToken;
import com.payment.service.model.User;
import com.payment.service.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    public RefreshTokenService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RefreshToken createRefreshToken(User user) {
        byte[] tokenBytes = new byte[64];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        RefreshToken refreshToken = new RefreshToken(
                token, user, Instant.now().plusMillis(refreshTokenExpiration));
        return repository.save(refreshToken);
    }

    @Transactional(readOnly = true)
    public RefreshToken validateToken(String token) {
        RefreshToken rt = repository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Refresh token not found"));

        if (rt.isRevoked() || rt.getExpiresAt().isBefore(Instant.now())) {
            throw new SecurityException("Refresh token is expired or revoked");
        }
        return rt;
    }

    @Transactional
    public void revokeAllUserTokens(User user) {
        repository.revokeAllUserTokens(user.getId());
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        repository.deleteExpiredAndRevokedTokens(Instant.now());
    }
}
