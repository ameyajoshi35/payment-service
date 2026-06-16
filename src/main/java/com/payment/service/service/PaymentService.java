package com.payment.service.service;

import com.payment.service.config.StripeConfig;
import com.payment.service.dto.request.AddFundsRequest;
import com.payment.service.dto.request.SendMoneyRequest;
import com.payment.service.dto.response.PaymentIntentResponse;
import com.payment.service.dto.response.TransactionResponse;
import com.payment.service.exception.InsufficientFundsException;
import com.payment.service.exception.PaymentException;
import com.payment.service.exception.ResourceNotFoundException;
import com.payment.service.model.Transaction;
import com.payment.service.model.User;
import com.payment.service.model.Wallet;
import com.payment.service.model.enums.TransactionStatus;
import com.payment.service.model.enums.TransactionType;
import com.payment.service.repository.TransactionRepository;
import com.payment.service.repository.UserRepository;
import com.payment.service.repository.WalletRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final WalletService walletService;
    private final StripeConfig stripeConfig;

    @Value("${payment.max-transfer-amount}")
    private BigDecimal maxTransferAmount;

    @Value("${payment.min-transfer-amount}")
    private BigDecimal minTransferAmount;

    public PaymentService(WalletRepository walletRepository,
                          UserRepository userRepository,
                          TransactionRepository transactionRepository,
                          WalletService walletService,
                          StripeConfig stripeConfig) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.walletService = walletService;
        this.stripeConfig = stripeConfig;
    }

    /**
     * Step 1 of deposit: create a Stripe PaymentIntent and return client_secret for frontend confirmation.
     * Wallet is credited once payment_intent.succeeded webhook fires (step 2).
     */
    @Transactional
    public PaymentIntentResponse createDepositIntent(User user, AddFundsRequest request, String idempotencyKey) {
        validateAmount(request.amount());

        Wallet wallet = walletService.findWalletByUserId(user.getId());

        long amountCents = request.amount().multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency("usd")
                    .setCustomer(user.getStripeCustomerId())
                    .setPaymentMethod(request.paymentMethodId())
                    .setConfirmationMethod(PaymentIntentCreateParams.ConfirmationMethod.MANUAL)
                    .setConfirm(true)
                    .setDescription(request.description() != null ? request.description() : "Wallet deposit")
                    .putMetadata("walletId", wallet.getId().toString())
                    .putMetadata("userId", user.getId().toString())
                    .build();

            PaymentIntent intent = PaymentIntent.create(params,
                    com.stripe.net.RequestOptions.builder()
                            .setIdempotencyKey(idempotencyKey)
                            .build());

            Transaction txn = new Transaction();
            txn.setToWallet(wallet);
            txn.setAmount(request.amount());
            txn.setCurrency("USD");
            txn.setType(TransactionType.DEPOSIT);
            txn.setStatus(TransactionStatus.PENDING);
            txn.setStripePaymentIntentId(intent.getId());
            txn.setIdempotencyKey(idempotencyKey);
            txn.setDescription(request.description() != null ? request.description() : "Wallet deposit");
            transactionRepository.save(txn);

            return new PaymentIntentResponse(
                    intent.getId(), intent.getClientSecret(),
                    request.amount(), "USD", intent.getStatus());

        } catch (StripeException e) {
            log.error("Stripe error creating deposit intent: {}", e.getMessage());
            throw new PaymentException("Payment processing failed: " + e.getMessage());
        }
    }

    /**
     * Called by the Stripe webhook when payment_intent.succeeded fires.
     * Idempotent — safe to call multiple times for the same intent.
     */
    @Transactional
    public void handleDepositSuccess(String paymentIntentId) {
        Transaction txn = transactionRepository.findByStripePaymentIntentId(paymentIntentId)
                .orElse(null);
        if (txn == null) {
            log.warn("No transaction found for PaymentIntent {}", paymentIntentId);
            return;
        }
        if (txn.getStatus() == TransactionStatus.COMPLETED) {
            log.info("Deposit {} already completed, skipping duplicate webhook", paymentIntentId);
            return;
        }

        Wallet wallet = walletRepository.findByIdWithLock(txn.getToWallet().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        wallet.setBalance(wallet.getBalance().add(txn.getAmount()));
        walletRepository.save(wallet);

        txn.setStatus(TransactionStatus.COMPLETED);
        transactionRepository.save(txn);

        log.info("Deposit of {} credited to wallet {}", txn.getAmount(), wallet.getId());
    }

    @Transactional
    public void handleDepositFailure(String paymentIntentId, String reason) {
        transactionRepository.findByStripePaymentIntentId(paymentIntentId).ifPresent(txn -> {
            txn.setStatus(TransactionStatus.FAILED);
            txn.setFailureReason(reason);
            transactionRepository.save(txn);
        });
    }

    /**
     * Atomic P2P transfer — debits sender and credits receiver in a single DB transaction.
     * Pessimistic locks on both wallets prevent double-spend races.
     */
    @Transactional
    public TransactionResponse sendMoney(User sender, SendMoneyRequest request, String idempotencyKey) {
        validateAmount(request.amount());

        User recipient = userRepository.findByEmail(request.recipientEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Recipient not found: " + request.recipientEmail()));

        if (sender.getId().equals(recipient.getId())) {
            throw new PaymentException("Cannot send money to yourself");
        }

        Wallet senderWallet = walletRepository.findByUserIdWithLock(sender.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Sender wallet not found"));
        Wallet recipientWallet = walletRepository.findByUserIdWithLock(recipient.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Recipient wallet not found"));

        if (!senderWallet.getCurrency().equals(recipientWallet.getCurrency())) {
            throw new PaymentException("Cross-currency transfers are not yet supported");
        }
        if (senderWallet.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException("Insufficient balance. Available: " + senderWallet.getBalance());
        }

        senderWallet.setBalance(senderWallet.getBalance().subtract(request.amount()));
        recipientWallet.setBalance(recipientWallet.getBalance().add(request.amount()));
        walletRepository.save(senderWallet);
        walletRepository.save(recipientWallet);

        Transaction txn = new Transaction();
        txn.setFromWallet(senderWallet);
        txn.setToWallet(recipientWallet);
        txn.setAmount(request.amount());
        txn.setCurrency(senderWallet.getCurrency());
        txn.setType(TransactionType.TRANSFER);
        txn.setStatus(TransactionStatus.COMPLETED);
        txn.setIdempotencyKey(idempotencyKey);
        txn.setDescription(request.note() != null ? request.note() : "Transfer to " + request.recipientEmail());
        txn = transactionRepository.save(txn);

        log.info("Transfer of {} from {} to {}", request.amount(), sender.getEmail(), request.recipientEmail());
        return walletService.toTransactionResponse(txn, senderWallet.getId());
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID transactionId, UUID userId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found"));

        Wallet userWallet = walletService.findWalletByUserId(userId);

        boolean isOwner = (txn.getFromWallet() != null && txn.getFromWallet().getId().equals(userWallet.getId()))
                || (txn.getToWallet() != null && txn.getToWallet().getId().equals(userWallet.getId()));

        if (!isOwner) {
            throw new PaymentException("Access denied to transaction");
        }

        return walletService.toTransactionResponse(txn, userWallet.getId());
    }

    private void validateAmount(BigDecimal amount) {
        if (amount.compareTo(minTransferAmount) < 0) {
            throw new PaymentException("Amount below minimum: " + minTransferAmount);
        }
        if (amount.compareTo(maxTransferAmount) > 0) {
            throw new PaymentException("Amount exceeds maximum: " + maxTransferAmount);
        }
    }
}
