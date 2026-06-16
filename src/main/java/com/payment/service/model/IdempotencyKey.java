package com.payment.service.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys", indexes = {
    @Index(name = "idx_idempotency_hash_user", columnList = "key_hash,user_id", unique = true)
})
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 255)
    private String endpoint;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected IdempotencyKey() {}

    public IdempotencyKey(String keyHash, UUID userId, String endpoint,
                          String responseBody, Integer responseStatus, Instant expiresAt) {
        this.keyHash = keyHash;
        this.userId = userId;
        this.endpoint = endpoint;
        this.responseBody = responseBody;
        this.responseStatus = responseStatus;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getKeyHash() { return keyHash; }
    public UUID getUserId() { return userId; }
    public String getEndpoint() { return endpoint; }
    public String getResponseBody() { return responseBody; }
    public Integer getResponseStatus() { return responseStatus; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
