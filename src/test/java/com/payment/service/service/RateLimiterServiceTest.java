package com.payment.service.service;

import com.payment.service.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RateLimiterServiceTest {

    private static final int MAX_REQUESTS = 5;
    private static final int WINDOW_SECONDS = 60;

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new RateLimiterService(MAX_REQUESTS, WINDOW_SECONDS);
    }

    @Test
    void checkRateLimit_underLimit_noException() {
        UUID userId = UUID.randomUUID();

        assertThatNoException().isThrownBy(() -> {
            for (int i = 0; i < MAX_REQUESTS; i++) {
                rateLimiterService.checkRateLimit(userId, "send");
            }
        });
    }

    @Test
    void checkRateLimit_overLimit_throwsRateLimitException() {
        UUID userId = UUID.randomUUID();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            rateLimiterService.checkRateLimit(userId, "send");
        }

        assertThatThrownBy(() -> rateLimiterService.checkRateLimit(userId, "send"))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("Too many requests");
    }

    @Test
    void checkRateLimit_differentEndpoints_trackedSeparately() {
        UUID userId = UUID.randomUUID();

        // Fill up the "send" bucket
        for (int i = 0; i < MAX_REQUESTS; i++) {
            rateLimiterService.checkRateLimit(userId, "send");
        }

        // "deposit" bucket is independent — should not throw
        assertThatNoException().isThrownBy(
                () -> rateLimiterService.checkRateLimit(userId, "deposit"));
    }

    @Test
    void checkRateLimit_differentUsers_trackedSeparately() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        // Fill user1's bucket
        for (int i = 0; i < MAX_REQUESTS; i++) {
            rateLimiterService.checkRateLimit(user1, "send");
        }

        // user2 is unaffected
        assertThatNoException().isThrownBy(
                () -> rateLimiterService.checkRateLimit(user2, "send"));
    }

    @Test
    void checkRateLimit_afterExceeding_counterNotIncremented() {
        UUID userId = UUID.randomUUID();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            rateLimiterService.checkRateLimit(userId, "send");
        }

        // Exceed the limit — should throw and not increment
        assertThatThrownBy(() -> rateLimiterService.checkRateLimit(userId, "send"))
                .isInstanceOf(RateLimitException.class);

        // Still throws (not 6 → 7 → ...)
        assertThatThrownBy(() -> rateLimiterService.checkRateLimit(userId, "send"))
                .isInstanceOf(RateLimitException.class);
    }
}
