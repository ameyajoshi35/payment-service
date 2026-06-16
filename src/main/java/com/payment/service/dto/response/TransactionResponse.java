package com.payment.service.dto.response;

import com.payment.service.model.enums.TransactionStatus;
import com.payment.service.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
    UUID id,
    BigDecimal amount,
    String currency,
    TransactionType type,
    TransactionStatus status,
    String description,
    String counterpartyEmail,
    String stripePaymentIntentId,
    Instant createdAt
) {}
