package com.payment.service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SendMoneyRequest(
    @NotBlank @Email String recipientEmail,
    @NotNull @DecimalMin(value = "0.50", message = "Minimum transfer is $0.50") BigDecimal amount,
    String note
) {}
