package com.payment.service.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
    UUID walletId,
    BigDecimal balance,
    String currency,
    boolean active,
    Instant updatedAt
) {}
