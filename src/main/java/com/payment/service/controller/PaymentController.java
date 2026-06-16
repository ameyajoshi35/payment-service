package com.payment.service.controller;

import com.payment.service.dto.request.AddFundsRequest;
import com.payment.service.dto.request.SendMoneyRequest;
import com.payment.service.dto.response.ApiResponse;
import com.payment.service.dto.response.PaymentIntentResponse;
import com.payment.service.dto.response.TransactionResponse;
import com.payment.service.exception.PaymentException;
import com.payment.service.exception.ResourceNotFoundException;
import com.payment.service.model.IdempotencyKey;
import com.payment.service.model.User;
import com.payment.service.repository.UserRepository;
import com.payment.service.service.IdempotencyService;
import com.payment.service.service.PaymentService;
import com.payment.service.service.RateLimiterService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final RateLimiterService rateLimiterService;
    private final UserRepository userRepository;

    public PaymentController(PaymentService paymentService,
                              IdempotencyService idempotencyService,
                              RateLimiterService rateLimiterService,
                              UserRepository userRepository) {
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
        this.rateLimiterService = rateLimiterService;
        this.userRepository = userRepository;
    }

    /**
     * POST /api/payments/deposit
     * Creates a Stripe PaymentIntent. Returns client_secret for Stripe.js card confirmation.
     * Header: X-Idempotency-Key (required, max 255 chars)
     */
    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<PaymentIntentResponse>> deposit(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody AddFundsRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey) {

        validateIdempotencyKey(idempotencyKey);
        User user = resolveUser(principal);

        log.info("Deposit requested userId={} amount={} idempotencyKey={}",
                user.getId(), request.amount(), idempotencyKey);

        rateLimiterService.checkRateLimit(user.getId(), "deposit");

        Optional<IdempotencyKey> existing = idempotencyService.findExistingResult(idempotencyKey, user.getId());
        if (existing.isPresent()) {
            log.warn("Duplicate deposit request detected userId={} idempotencyKey={}",
                    user.getId(), idempotencyKey);
            PaymentIntentResponse cached = idempotencyService.deserializeResponse(
                    existing.get(), PaymentIntentResponse.class);
            return ResponseEntity.ok(ApiResponse.ok("Duplicate request - returning cached response", cached));
        }

        PaymentIntentResponse response = paymentService.createDepositIntent(user, request, idempotencyKey);
        idempotencyService.storeResult(idempotencyKey, user.getId(), "deposit", response, 200);

        log.info("Deposit intent created userId={} paymentIntentId={} amount={}",
                user.getId(), response.paymentIntentId(), response.amount());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * POST /api/payments/send
     * P2P wallet transfer. Debits sender, credits recipient atomically.
     * Header: X-Idempotency-Key (required, max 255 chars)
     */
    @PostMapping("/send")
    public ResponseEntity<ApiResponse<TransactionResponse>> send(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody SendMoneyRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey) {

        validateIdempotencyKey(idempotencyKey);
        User user = resolveUser(principal);

        log.info("Transfer requested senderId={} recipientEmail={} amount={} idempotencyKey={}",
                user.getId(), request.recipientEmail(), request.amount(), idempotencyKey);

        rateLimiterService.checkRateLimit(user.getId(), "send");

        Optional<IdempotencyKey> existing = idempotencyService.findExistingResult(idempotencyKey, user.getId());
        if (existing.isPresent()) {
            log.warn("Duplicate send request detected userId={} idempotencyKey={}",
                    user.getId(), idempotencyKey);
            TransactionResponse cached = idempotencyService.deserializeResponse(
                    existing.get(), TransactionResponse.class);
            return ResponseEntity.ok(ApiResponse.ok("Duplicate request - returning cached response", cached));
        }

        TransactionResponse response = paymentService.sendMoney(user, request, idempotencyKey);
        idempotencyService.storeResult(idempotencyKey, user.getId(), "send", response, 200);

        log.info("Transfer completed txnId={} senderId={} amount={}",
                response.id(), user.getId(), response.amount());
        return ResponseEntity.ok(ApiResponse.ok("Transfer completed", response));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID transactionId) {

        UUID userId = UUID.fromString(principal.getUsername());
        log.debug("Transaction lookup userId={} transactionId={}", userId, transactionId);

        TransactionResponse response = paymentService.getTransaction(transactionId, userId);

        log.debug("Transaction found transactionId={} status={}", transactionId, response.status());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    private void validateIdempotencyKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new PaymentException("X-Idempotency-Key header must not be blank");
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new PaymentException("X-Idempotency-Key must not exceed " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        // Reject keys with control characters or whitespace that could cause hash collisions
        if (key.chars().anyMatch(c -> c < 32)) {
            throw new PaymentException("X-Idempotency-Key contains invalid characters");
        }
    }

    private User resolveUser(UserDetails principal) {
        return userRepository.findById(UUID.fromString(principal.getUsername()))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
