package com.payment.service.service;

import com.payment.service.config.StripeConfig;
import com.payment.service.dto.request.SendMoneyRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private UserRepository userRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private WalletService walletService;
    @Mock private StripeConfig stripeConfig;

    private PaymentService paymentService;

    private User sender;
    private User recipient;
    private Wallet senderWallet;
    private Wallet recipientWallet;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(walletRepository, userRepository,
                transactionRepository, walletService, stripeConfig);
        ReflectionTestUtils.setField(paymentService, "maxTransferAmount", new BigDecimal("10000.00"));
        ReflectionTestUtils.setField(paymentService, "minTransferAmount", new BigDecimal("0.50"));

        sender = makeUser("sender@example.com");
        recipient = makeUser("recipient@example.com");
        senderWallet = makeWallet(sender, new BigDecimal("500.00"));
        recipientWallet = makeWallet(recipient, new BigDecimal("100.00"));
    }

    // ── sendMoney ─────────────────────────────────────────────────────────────

    @Test
    void sendMoney_success_debitsAndCreditsCorrectly() {
        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("75.00"), "for coffee");

        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipient));
        when(walletRepository.findByUserIdWithLock(sender.getId())).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByUserIdWithLock(recipient.getId())).thenReturn(Optional.of(recipientWallet));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });
        when(walletService.toTransactionResponse(any(), any())).thenReturn(stubTransactionResponse());

        paymentService.sendMoney(sender, req, "key-123");

        ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository, times(2)).save(walletCaptor.capture());

        Wallet savedSender = walletCaptor.getAllValues().stream()
                .filter(w -> w.getUser().getEmail().equals("sender@example.com"))
                .findFirst().orElseThrow();
        Wallet savedRecipient = walletCaptor.getAllValues().stream()
                .filter(w -> w.getUser().getEmail().equals("recipient@example.com"))
                .findFirst().orElseThrow();

        assertThat(savedSender.getBalance()).isEqualByComparingTo("425.00");   // 500 - 75
        assertThat(savedRecipient.getBalance()).isEqualByComparingTo("175.00"); // 100 + 75
    }

    @Test
    void sendMoney_savesTransactionWithCorrectFields() {
        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("50.00"), "rent");

        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipient));
        when(walletRepository.findByUserIdWithLock(sender.getId())).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByUserIdWithLock(recipient.getId())).thenReturn(Optional.of(recipientWallet));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });
        when(walletService.toTransactionResponse(any(), any())).thenReturn(stubTransactionResponse());

        paymentService.sendMoney(sender, req, "idm-key");

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        Transaction saved = txnCaptor.getValue();

        assertThat(saved.getAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(saved.getIdempotencyKey()).isEqualTo("idm-key");
        assertThat(saved.getDescription()).isEqualTo("rent");
    }

    @Test
    void sendMoney_insufficientFunds_throwsInsufficientFundsException() {
        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("999.00"), null);

        when(userRepository.findByEmail("recipient@example.com")).thenReturn(Optional.of(recipient));
        when(walletRepository.findByUserIdWithLock(sender.getId())).thenReturn(Optional.of(senderWallet));
        when(walletRepository.findByUserIdWithLock(recipient.getId())).thenReturn(Optional.of(recipientWallet));

        assertThatThrownBy(() -> paymentService.sendMoney(sender, req, "key"))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient balance");

        verify(walletRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void sendMoney_recipientNotFound_throwsResourceNotFoundException() {
        SendMoneyRequest req = new SendMoneyRequest("nobody@example.com", new BigDecimal("10.00"), null);

        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.sendMoney(sender, req, "key"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Recipient not found");
    }

    @Test
    void sendMoney_selfTransfer_throwsPaymentException() {
        SendMoneyRequest req = new SendMoneyRequest(sender.getEmail(), new BigDecimal("10.00"), null);

        when(userRepository.findByEmail(sender.getEmail())).thenReturn(Optional.of(sender));

        assertThatThrownBy(() -> paymentService.sendMoney(sender, req, "key"))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Cannot send money to yourself");
    }

    @Test
    void sendMoney_belowMinimum_throwsPaymentException() {
        // validateAmount() throws before any repo call, so no stubs needed
        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("0.01"), null);

        assertThatThrownBy(() -> paymentService.sendMoney(sender, req, "key"))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("minimum");
    }

    @Test
    void sendMoney_aboveMaximum_throwsPaymentException() {
        // validateAmount() throws before any repo call, so no stubs needed
        SendMoneyRequest req = new SendMoneyRequest("recipient@example.com", new BigDecimal("99999.00"), null);

        assertThatThrownBy(() -> paymentService.sendMoney(sender, req, "key"))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("maximum");
    }

    // ── handleDepositSuccess ──────────────────────────────────────────────────

    @Test
    void handleDepositSuccess_creditsWalletAndCompletesTransaction() {
        Transaction txn = makePendingDeposit(recipientWallet, new BigDecimal("200.00"), "pi_test_123");

        when(transactionRepository.findByStripePaymentIntentId("pi_test_123")).thenReturn(Optional.of(txn));
        when(walletRepository.findByIdWithLock(recipientWallet.getId())).thenReturn(Optional.of(recipientWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentService.handleDepositSuccess("pi_test_123");

        assertThat(recipientWallet.getBalance()).isEqualByComparingTo("300.00"); // 100 + 200
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void handleDepositSuccess_alreadyCompleted_isIdempotent() {
        Transaction txn = makePendingDeposit(recipientWallet, new BigDecimal("200.00"), "pi_test_123");
        txn.setStatus(TransactionStatus.COMPLETED);

        when(transactionRepository.findByStripePaymentIntentId("pi_test_123")).thenReturn(Optional.of(txn));

        paymentService.handleDepositSuccess("pi_test_123");

        // Should not touch the wallet or save again
        verify(walletRepository, never()).findByIdWithLock(any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void handleDepositSuccess_unknownPaymentIntent_isNoOp() {
        when(transactionRepository.findByStripePaymentIntentId("pi_unknown")).thenReturn(Optional.empty());

        // Should not throw, just log a warning
        assertThatNoException().isThrownBy(() -> paymentService.handleDepositSuccess("pi_unknown"));
        verify(walletRepository, never()).save(any());
    }

    // ── handleDepositFailure ──────────────────────────────────────────────────

    @Test
    void handleDepositFailure_marksTransactionFailed() {
        Transaction txn = makePendingDeposit(recipientWallet, new BigDecimal("50.00"), "pi_fail_456");
        when(transactionRepository.findByStripePaymentIntentId("pi_fail_456")).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentService.handleDepositFailure("pi_fail_456", "Card declined");

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(txn.getFailureReason()).isEqualTo("Card declined");
    }

    // ── getTransaction ────────────────────────────────────────────────────────

    @Test
    void getTransaction_ownerCanView() {
        Transaction txn = makePendingDeposit(senderWallet, new BigDecimal("100.00"), "pi_123");
        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(walletService.findWalletByUserId(sender.getId())).thenReturn(senderWallet);
        when(walletService.toTransactionResponse(any(), any())).thenReturn(stubTransactionResponse());

        assertThatNoException().isThrownBy(
                () -> paymentService.getTransaction(txn.getId(), sender.getId()));
    }

    @Test
    void getTransaction_nonOwner_throwsPaymentException() {
        User stranger = makeUser("stranger@example.com");
        Wallet strangerWallet = makeWallet(stranger, BigDecimal.ZERO);
        Transaction txn = makePendingDeposit(senderWallet, new BigDecimal("100.00"), "pi_123");

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(walletService.findWalletByUserId(stranger.getId())).thenReturn(strangerWallet);

        assertThatThrownBy(() -> paymentService.getTransaction(txn.getId(), stranger.getId()))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void getTransaction_notFound_throwsResourceNotFoundException() {
        UUID randomId = UUID.randomUUID();
        when(transactionRepository.findById(randomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getTransaction(randomId, sender.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private User makeUser(String email) {
        User u = new User();
        u.setEmail(email);
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private Wallet makeWallet(User user, BigDecimal balance) {
        Wallet w = new Wallet(user);
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        w.setBalance(balance);
        return w;
    }

    private Transaction makePendingDeposit(Wallet toWallet, BigDecimal amount, String stripeId) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setToWallet(toWallet);
        t.setAmount(amount);
        t.setType(TransactionType.DEPOSIT);
        t.setStatus(TransactionStatus.PENDING);
        t.setStripePaymentIntentId(stripeId);
        t.setCurrency("USD");
        return t;
    }

    private TransactionResponse stubTransactionResponse() {
        return new TransactionResponse(UUID.randomUUID(), new BigDecimal("100.00"), "USD",
                TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, null, null, null);
    }
}
