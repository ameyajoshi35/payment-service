package com.payment.service.service;

import com.payment.service.dto.response.TransactionResponse;
import com.payment.service.dto.response.WalletResponse;
import com.payment.service.exception.ResourceNotFoundException;
import com.payment.service.model.Transaction;
import com.payment.service.model.User;
import com.payment.service.model.Wallet;
import com.payment.service.model.enums.TransactionStatus;
import com.payment.service.model.enums.TransactionType;
import com.payment.service.repository.TransactionRepository;
import com.payment.service.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;

    private WalletService walletService;

    private User testUser;
    private Wallet testWallet;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, transactionRepository);

        testUser = makeUser("owner@example.com");
        testWallet = makeWallet(testUser, new BigDecimal("250.00"));
    }

    // ── getWallet ──────────────────────────────────────────────────────────────

    @Test
    void getWallet_returnsCorrectBalance() {
        when(walletRepository.findByUserId(testUser.getId())).thenReturn(Optional.of(testWallet));

        WalletResponse response = walletService.getWallet(testUser.getId());

        assertThat(response.balance()).isEqualByComparingTo("250.00");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.active()).isTrue();
    }

    @Test
    void getWallet_walletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findByUserId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getWallet(testUser.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Wallet not found");
    }

    // ── getTransactionHistory ──────────────────────────────────────────────────

    @Test
    void getTransactionHistory_returnsMappedPage() {
        Transaction txn = makeTransfer(testWallet, makeWallet(makeUser("other@example.com"), BigDecimal.ZERO),
                new BigDecimal("50.00"));
        Page<Transaction> page = new PageImpl<>(List.of(txn));

        when(walletRepository.findByUserId(testUser.getId())).thenReturn(Optional.of(testWallet));
        when(transactionRepository.findByWalletId(eq(testWallet.getId()), any())).thenReturn(page);

        Page<TransactionResponse> result = walletService.getTransactionHistory(
                testUser.getId(), PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).amount()).isEqualByComparingTo("50.00");
    }

    // ── toTransactionResponse ──────────────────────────────────────────────────

    @Test
    void toTransactionResponse_deposit_noCounterparty() {
        Transaction deposit = makeDeposit(testWallet, new BigDecimal("100.00"));

        TransactionResponse response = walletService.toTransactionResponse(deposit, testWallet.getId());

        assertThat(response.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.counterpartyEmail()).isNull();
        assertThat(response.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void toTransactionResponse_outgoingTransfer_showsRecipientEmail() {
        User recipient = makeUser("recipient@example.com");
        Wallet recipientWallet = makeWallet(recipient, BigDecimal.ZERO);
        Transaction txn = makeTransfer(testWallet, recipientWallet, new BigDecimal("30.00"));

        TransactionResponse response = walletService.toTransactionResponse(txn, testWallet.getId());

        assertThat(response.counterpartyEmail()).isEqualTo("recipient@example.com");
    }

    @Test
    void toTransactionResponse_incomingTransfer_showsSenderEmail() {
        User sender = makeUser("sender@example.com");
        Wallet senderWallet = makeWallet(sender, new BigDecimal("500.00"));
        Transaction txn = makeTransfer(senderWallet, testWallet, new BigDecimal("75.00"));

        TransactionResponse response = walletService.toTransactionResponse(txn, testWallet.getId());

        assertThat(response.counterpartyEmail()).isEqualTo("sender@example.com");
    }

    @Test
    void toTransactionResponse_completedStatus_reflected() {
        Transaction txn = makeDeposit(testWallet, new BigDecimal("50.00"));
        txn.setStatus(TransactionStatus.COMPLETED);

        TransactionResponse response = walletService.toTransactionResponse(txn, testWallet.getId());

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private User makeUser(String email) {
        User u = new User();
        u.setEmail(email);
        // Reflectively set UUID since it's generated by JPA in prod
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private Wallet makeWallet(User user, BigDecimal balance) {
        Wallet w = new Wallet(user);
        org.springframework.test.util.ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        w.setBalance(balance);
        return w;
    }

    private Transaction makeDeposit(Wallet toWallet, BigDecimal amount) {
        Transaction t = new Transaction();
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setToWallet(toWallet);
        t.setAmount(amount);
        t.setType(TransactionType.DEPOSIT);
        t.setStatus(TransactionStatus.PENDING);
        t.setCurrency("USD");
        return t;
    }

    private Transaction makeTransfer(Wallet from, Wallet to, BigDecimal amount) {
        Transaction t = new Transaction();
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setFromWallet(from);
        t.setToWallet(to);
        t.setAmount(amount);
        t.setType(TransactionType.TRANSFER);
        t.setStatus(TransactionStatus.COMPLETED);
        t.setCurrency("USD");
        return t;
    }
}
