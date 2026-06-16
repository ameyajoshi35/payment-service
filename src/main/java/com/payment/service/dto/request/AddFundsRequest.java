package com.payment.service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AddFundsRequest(
    @NotNull @DecimalMin(value = "0.50", message = "Minimum deposit is $0.50") BigDecimal amount,
    @NotNull String paymentMethodId,   // Stripe PaymentMethod ID from frontend
    String description
) {}
