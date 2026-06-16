package com.payment.service.controller;

import com.payment.service.config.StripeConfig;
import com.payment.service.service.PaymentService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final StripeConfig stripeConfig;
    private final PaymentService paymentService;

    public WebhookController(StripeConfig stripeConfig, PaymentService paymentService) {
        this.stripeConfig = stripeConfig;
        this.paymentService = paymentService;
    }

    /**
     * Stripe posts events here on payment state changes.
     * Signature verified via HMAC-SHA256 before any processing.
     */
    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        if (!StringUtils.hasText(sigHeader)) {
            log.warn("Stripe webhook received with missing Stripe-Signature header");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing signature");
        }

        if (!StringUtils.hasText(payload)) {
            log.warn("Stripe webhook received with empty payload");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Empty payload");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, stripeConfig.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature — possible spoofed request: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        }

        log.info("Stripe event received type={} eventId={}", event.getType(), event.getId());

        switch (event.getType()) {
            case "payment_intent.succeeded" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isEmpty()) {
                    log.error("Could not deserialize PaymentIntent from event eventId={}", event.getId());
                    return ResponseEntity.ok("Received");
                }
                PaymentIntent intent = (PaymentIntent) obj.get();
                log.info("PaymentIntent succeeded paymentIntentId={} amount={}",
                        intent.getId(), intent.getAmount());
                paymentService.handleDepositSuccess(intent.getId());
            }

            case "payment_intent.payment_failed" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isEmpty()) {
                    log.error("Could not deserialize failed PaymentIntent from event eventId={}", event.getId());
                    return ResponseEntity.ok("Received");
                }
                PaymentIntent intent = (PaymentIntent) obj.get();
                String reason = intent.getLastPaymentError() != null
                        ? intent.getLastPaymentError().getMessage() : "Payment failed";
                log.warn("PaymentIntent failed paymentIntentId={} reason={}", intent.getId(), reason);
                paymentService.handleDepositFailure(intent.getId(), reason);
            }

            default -> log.debug("Unhandled Stripe event type={} eventId={}", event.getType(), event.getId());
        }

        return ResponseEntity.ok("Received");
    }
}
