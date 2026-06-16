package com.payment.service.controller;

import com.payment.service.dto.response.ApiResponse;
import com.payment.service.dto.response.TransactionResponse;
import com.payment.service.dto.response.WalletResponse;
import com.payment.service.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<WalletResponse>> getWallet(
            @AuthenticationPrincipal UserDetails principal) {

        UUID userId = UUID.fromString(principal.getUsername());
        log.debug("Wallet balance requested userId={}", userId);

        WalletResponse wallet = walletService.getWallet(userId);

        log.debug("Wallet fetched userId={} balance={} currency={}",
                userId, wallet.balance(), wallet.currency());
        return ResponseEntity.ok(ApiResponse.ok(wallet));
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getTransactions(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        UUID userId = UUID.fromString(principal.getUsername());
        log.debug("Transaction history requested userId={} page={} size={}",
                userId, pageable.getPageNumber(), pageable.getPageSize());

        Page<TransactionResponse> page = walletService.getTransactionHistory(userId, pageable);

        log.debug("Returned {} transactions for userId={}", page.getNumberOfElements(), userId);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }
}
