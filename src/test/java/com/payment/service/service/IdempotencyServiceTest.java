package com.payment.service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.service.exception.IdempotencyConflictException;
import com.payment.service.model.IdempotencyKey;
import com.payment.service.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyService idempotencyService;

    private final UUID userId = UUID.randomUUID();
    private final String rawKey = "test-idempotency-key-123";

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(repository, new ObjectMapper());
        ReflectionTestUtils.setField(idempotencyService, "ttlHours", 24);
    }

    @Test
    void findExistingResult_whenKeyExists_returnsRecord() {
        IdempotencyKey record = stubIdempotencyKey();
        when(repository.findByKeyHashAndUserId(any(), eq(userId))).thenReturn(Optional.of(record));

        Optional<IdempotencyKey> result = idempotencyService.findExistingResult(rawKey, userId);

        assertThat(result).isPresent();
    }

    @Test
    void findExistingResult_whenKeyAbsent_returnsEmpty() {
        when(repository.findByKeyHashAndUserId(any(), eq(userId))).thenReturn(Optional.empty());

        Optional<IdempotencyKey> result = idempotencyService.findExistingResult(rawKey, userId);

        assertThat(result).isEmpty();
    }

    @Test
    void storeResult_savesRecordWithCorrectFields() {
        when(repository.findByKeyHashAndUserId(any(), eq(userId))).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        idempotencyService.storeResult(rawKey, userId, "deposit", "response-body", 200);

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(captor.capture());

        IdempotencyKey saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getEndpoint()).isEqualTo("deposit");
        assertThat(saved.getResponseStatus()).isEqualTo(200);
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void storeResult_whenDuplicateConcurrentRequest_throwsConflict() {
        IdempotencyKey existing = stubIdempotencyKey();
        // Simulate a concurrent request that stored the key between our check and save
        when(repository.findByKeyHashAndUserId(any(), eq(userId))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                idempotencyService.storeResult(rawKey, userId, "deposit", "body", 200))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void computeHash_sameInputsProduceSameHash() {
        // Two lookups with identical key and userId should hit the same DB record
        when(repository.findByKeyHashAndUserId(any(), eq(userId))).thenReturn(Optional.empty());

        idempotencyService.findExistingResult(rawKey, userId);
        idempotencyService.findExistingResult(rawKey, userId);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).findByKeyHashAndUserId(hashCaptor.capture(), eq(userId));

        assertThat(hashCaptor.getAllValues().get(0))
                .isEqualTo(hashCaptor.getAllValues().get(1));
    }

    @Test
    void computeHash_differentUsersProduceDifferentHashes() {
        UUID otherUserId = UUID.randomUUID();
        when(repository.findByKeyHashAndUserId(any(), any())).thenReturn(Optional.empty());

        idempotencyService.findExistingResult(rawKey, userId);
        idempotencyService.findExistingResult(rawKey, otherUserId);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).findByKeyHashAndUserId(hashCaptor.capture(), any());

        // Same raw key but different userId must produce different hashes (prevents cross-user replay)
        assertThat(hashCaptor.getAllValues().get(0))
                .isNotEqualTo(hashCaptor.getAllValues().get(1));
    }

    @Test
    void purgeExpiredKeys_delegatesToRepository() {
        when(repository.deleteExpiredKeys(any())).thenReturn(3);

        idempotencyService.purgeExpiredKeys();

        verify(repository).deleteExpiredKeys(any(Instant.class));
    }

    @Test
    void deserializeResponse_validJson_returnsObject() throws Exception {
        IdempotencyKey key = new IdempotencyKey(
                "hash", userId, "send", "{\"message\":\"ok\"}", 200,
                Instant.now().plusSeconds(3600));

        // Deserialize to a simple map
        Object result = idempotencyService.deserializeResponse(key, Object.class);

        assertThat(result).isNotNull();
    }

    private IdempotencyKey stubIdempotencyKey() {
        return new IdempotencyKey(
                "hashvalue", userId, "deposit", "{}", 200,
                Instant.now().plusSeconds(3600));
    }
}
