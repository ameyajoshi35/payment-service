package com.payment.service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.service.exception.IdempotencyConflictException;
import com.payment.service.model.IdempotencyKey;
import com.payment.service.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${payment.idempotency-key-ttl-hours}")
    private int ttlHours;

    public IdempotencyService(IdempotencyKeyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Optional<IdempotencyKey> findExistingResult(String rawKey, UUID userId) {
        String hash = computeHash(rawKey, userId);
        return repository.findByKeyHashAndUserId(hash, userId);
    }

    @Transactional
    public void storeResult(String rawKey, UUID userId, String endpoint, Object responseBody, int status) {
        String hash = computeHash(rawKey, userId);

        if (repository.findByKeyHashAndUserId(hash, userId).isPresent()) {
            throw new IdempotencyConflictException("Duplicate request detected for idempotency key");
        }

        try {
            IdempotencyKey record = new IdempotencyKey(
                    hash, userId, endpoint,
                    objectMapper.writeValueAsString(responseBody),
                    status,
                    Instant.now().plusSeconds(ttlHours * 3600L));
            repository.save(record);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize idempotency response body", e);
        }
    }

    public <T> T deserializeResponse(IdempotencyKey key, Class<T> type) {
        try {
            return objectMapper.readValue(key.getResponseBody(), type);
        } catch (JsonProcessingException e) {
            log.warn("Could not deserialize cached idempotency response", e);
            return null;
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredKeys() {
        int deleted = repository.deleteExpiredKeys(Instant.now());
        if (deleted > 0) log.info("Purged {} expired idempotency keys", deleted);
    }

    private String computeHash(String rawKey, UUID userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = userId.toString() + ":" + rawKey;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
