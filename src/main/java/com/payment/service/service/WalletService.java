package com.payment.service.service;

import com.payment.service.dto.response.TransactionResponse;
import com.payment.service.dto.response.WalletResponse;
import com.payment.service.exception.ResourceNotFoundException;
import com.payment.service.model.Transaction;
import com.payment.service.model.Wallet;
import com.payment.service.repository.TransactionRepository;
import com.payment.service.repository.WalletRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public WalletService(WalletRepository walletRepository, TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID userId) {
        Wallet wallet = findWalletByUserId(userId);
        return toWalletResponse(wallet);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactionHistory(UUID userId, Pageable pageable) {
        Wallet wallet = findWalletByUserId(userId);
        return transactionRepository
                .findByWalletId(wallet.getId(), pageable)
                .map(txn -> toTransactionResponse(txn, wallet.getId()));
    }

    public Wallet findWalletByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user"));
    }

    public WalletResponse toWalletResponse(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(), wallet.getBalance(), wallet.getCurrency(),
                wallet.isActive(), wallet.getUpdatedAt());
    }

    public TransactionResponse toTransactionResponse(Transaction txn, UUID viewerWalletId) {
        String counterparty = null;
        if (txn.getFromWallet() != null && !txn.getFromWallet().getId().equals(viewerWalletId)) {
            counterparty = txn.getFromWallet().getUser().getEmail();
        } else if (txn.getToWallet() != null && !txn.getToWallet().getId().equals(viewerWalletId)) {
            counterparty = txn.getToWallet().getUser().getEmail();
        }

        return new TransactionResponse(
                txn.getId(), txn.getAmount(), txn.getCurrency(),
                txn.getType(), txn.getStatus(), txn.getDescription(),
                counterparty, txn.getStripePaymentIntentId(), txn.getCreatedAt());
    }
}
