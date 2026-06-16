package com.payment.service.model.enums;

public enum TransactionType {
    DEPOSIT,      // funds added from external card via Stripe
    WITHDRAWAL,   // funds sent out to external bank
    TRANSFER      // P2P wallet-to-wallet
}