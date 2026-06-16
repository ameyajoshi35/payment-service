package com.payment.service.service;

import com.payment.service.exception.RateLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RateLimiterService {

    private final int maxRequests;
    private final long windowSizeMs;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimiterService(
            @Value("${payment.rate-limit.requests-per-minute}") int maxRequests,
            @Value("${payment.rate-limit.window-size-seconds}") long windowSizeSeconds) {
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeSeconds * 1000L;
    }

    public void checkRateLimit(UUID userId, String endpoint) {
        String key = userId + ":" + endpoint;
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter());

        long now = System.currentTimeMillis();
        counter.evictOldRequests(now, windowSizeMs);

        if (counter.increment() > maxRequests) {
            counter.decrement();
            throw new RateLimitException("Too many requests. Limit: " + maxRequests + " per minute.");
        }
    }

    private static class WindowCounter {
        private final ConcurrentHashMap<Long, AtomicInteger> buckets = new ConcurrentHashMap<>();
        private final AtomicLong total = new AtomicLong(0);

        int increment() {
            long bucket = System.currentTimeMillis() / 1000L;
            buckets.computeIfAbsent(bucket, k -> new AtomicInteger(0)).incrementAndGet();
            return (int) total.incrementAndGet();
        }

        void decrement() {
            total.decrementAndGet();
        }

        void evictOldRequests(long now, long windowMs) {
            long cutoffSecond = (now - windowMs) / 1000L;
            buckets.keySet().removeIf(bucket -> {
                if (bucket < cutoffSecond) {
                    AtomicInteger count = buckets.get(bucket);
                    if (count != null) total.addAndGet(-count.get());
                    return true;
                }
                return false;
            });
        }
    }
}
