package com.payment.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.service.dto.request.SendMoneyRequest;
import com.payment.service.dto.response.TransactionResponse;
import com.payment.service.exception.GlobalExceptionHandler;
import com.payment.service.exception.InsufficientFundsException;
import com.payment.service.exception.RateLimitException;
import com.payment.service.model.User;
import com.payment.service.model.enums.TransactionStatus;
import com.payment.service.model.enums.TransactionType;
import com.payment.service.repository.UserRepository;
import com.payment.service.service.IdempotencyService;
import com.payment.service.service.PaymentService;
import com.payment.service.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {PaymentController.class, GlobalExceptionHandler.class})
@Import(TestSecurityConfig.class)
class PaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PaymentService paymentService;
    @MockBean IdempotencyService idempotencyService;
    @MockBean RateLimiterService rateLimiterService;
    @MockBean UserRepository userRepository;
    @MockBean com.payment.service.security.JwtTokenProvider jwtTokenProvider;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String IDEMPOTENCY_KEY = "unique-key-abc-123";

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("sender@example.com");
        ReflectionTestUtils.setField(user, "id", USER_ID);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(idempotencyService.findExistingResult(any(), any())).thenReturn(Optional.empty());
    }

    // ── POST /api/payments/send ───────────────────────────────────────────────

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void send_validRequest_returns200() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = new User();
        user.setEmail("sender@example.com");
        ReflectionTestUtils.setField(user, "id", userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(paymentService.sendMoney(any(), any(), any())).thenReturn(stubTransactionResponse());

        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("50.00"), "lunch");

        mockMvc.perform(post("/api/payments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", IDEMPOTENCY_KEY)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.amount").value(100.00));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void send_missingIdempotencyKey_returns400() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("50.00"), null);

        mockMvc.perform(post("/api/payments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void send_insufficientFunds_returns422() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = new User();
        user.setEmail("sender@example.com");
        ReflectionTestUtils.setField(user, "id", userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(paymentService.sendMoney(any(), any(), any()))
                .thenThrow(new InsufficientFundsException("Insufficient balance. Available: 10.00"));

        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("999.00"), null);

        mockMvc.perform(post("/api/payments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", IDEMPOTENCY_KEY)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient balance")));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void send_rateLimitExceeded_returns429() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = new User();
        user.setEmail("sender@example.com");
        ReflectionTestUtils.setField(user, "id", userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new RateLimitException("Too many requests. Limit: 10 per minute."))
                .when(rateLimiterService).checkRateLimit(any(), eq("send"));

        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("10.00"), null);

        mockMvc.perform(post("/api/payments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", IDEMPOTENCY_KEY)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Too many requests")));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void send_idempotencyKeyTooLong_returns400() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        String tooLongKey = "x".repeat(256);
        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("10.00"), null);

        mockMvc.perform(post("/api/payments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", tooLongKey)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("255 characters")));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void send_invalidRecipientEmail_returns400() throws Exception {
        SendMoneyRequest req = new SendMoneyRequest("not-an-email", new BigDecimal("10.00"), null);

        mockMvc.perform(post("/api/payments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", IDEMPOTENCY_KEY)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void send_amountBelowMinimum_returns400() throws Exception {
        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("0.10"), null);

        mockMvc.perform(post("/api/payments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", IDEMPOTENCY_KEY)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void send_duplicateIdempotencyKey_returnsCachedResponse() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = new User();
        user.setEmail("sender@example.com");
        ReflectionTestUtils.setField(user, "id", userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        com.payment.service.model.IdempotencyKey existingKey =
                new com.payment.service.model.IdempotencyKey(
                        "hash", userId, "send",
                        objectMapper.writeValueAsString(stubTransactionResponse()),
                        200, Instant.now().plusSeconds(3600));

        when(idempotencyService.findExistingResult(eq(IDEMPOTENCY_KEY), eq(userId)))
                .thenReturn(Optional.of(existingKey));
        when(idempotencyService.deserializeResponse(any(), any())).thenReturn(stubTransactionResponse());

        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("50.00"), null);

        mockMvc.perform(post("/api/payments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", IDEMPOTENCY_KEY)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Duplicate request - returning cached response"));

        verify(paymentService, never()).sendMoney(any(), any(), any());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private TransactionResponse stubTransactionResponse() {
        return new TransactionResponse(
                UUID.randomUUID(), new BigDecimal("100.00"), "USD",
                TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                "lunch", "recipient@example.com", null, Instant.now());
    }
}
