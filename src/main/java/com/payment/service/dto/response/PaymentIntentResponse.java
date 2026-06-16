package com.payment.service.dto.response;

import java.math.BigDecimal;

public record PaymentIntentResponse(
    String paymentIntentId,
    String clientSecret,
    BigDecimal amount,
    String currency,
    String status
) {}
