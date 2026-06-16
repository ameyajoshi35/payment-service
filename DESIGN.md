# Payment Service — Design Document

## 1. Overview

A RESTful P2P payment service built on Spring Boot 3.2, integrating with the Stripe API for card-based funding. Users can register, maintain a wallet, send money to other users, and add funds via a credit/debit card. The system is designed to be secure, idempotent, and safe under concurrent load.

---

## 2. Goals

| Goal | Approach |
|------|----------|
| Secure authentication | Stateless JWT with short-lived access tokens and revocable refresh tokens |
| Safe concurrent payments | Pessimistic DB locking on wallet balance mutations |
| Idempotent payments | SHA-256 keyed idempotency store with 24h TTL |
| Stripe card funding | PaymentIntent creation + webhook-driven wallet credit |
| P2P transfers | Internal wallet-to-wallet debit/credit in a single transaction |
| Abuse prevention | Per-user, per-endpoint sliding-window rate limiting |

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Client (mobile/web)               │
└──────────────────────┬──────────────────────────────┘
                       │ HTTPS
                       ▼
┌─────────────────────────────────────────────────────┐
│                Spring Boot Application              │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌────────────────────┐│
│  │   Auth   │  │  Wallet  │  │  Payment /Webhook  ││
│  │Controller│  │Controller│  │    Controller      ││
│  └────┬─────┘  └────┬─────┘  └────────┬───────────┘│
│       │             │                 │             │
│  ┌────▼─────────────▼─────────────────▼───────────┐│
│  │          Service Layer                          ││
│  │  AuthService  WalletService  PaymentService     ││
│  │  IdempotencyService  RefreshTokenService        ││
│  │  RateLimiterService                             ││
│  └────────────────────┬────────────────────────────┘│
│                       │                             │
│  ┌────────────────────▼────────────────────────────┐│
│  │         Spring Data JPA Repositories            ││
│  └────────────────────┬────────────────────────────┘│
│                       │                             │
│  ┌────────────────────▼────────────────────────────┐│
│  │      H2 (dev) / PostgreSQL (prod)               ││
│  └─────────────────────────────────────────────────┘│
│                                                     │
│  ┌─────────────────────────────────────────────────┐│
│  │       Spring Security Filter Chain              ││
│  │   JwtAuthenticationFilter (OncePerRequestFilter)││
│  └─────────────────────────────────────────────────┘│
└──────────────────────┬──────────────────────────────┘
                       │ Stripe Java SDK
                       ▼
             ┌──────────────────┐
             │   Stripe API     │
             │  (PaymentIntent, │
             │   Customer)      │
             └──────────────────┘
```

---

## 4. Data Model

### 4.1 Entity Relationship Diagram

```
┌───────────────┐       ┌───────────────┐
│     User      │──1:1──│    Wallet     │
│───────────────│       │───────────────│
│ id (UUID, PK) │       │ id (UUID, PK) │
│ email         │       │ user_id (FK)  │
│ phone_number  │       │ balance       │◄── BigDecimal(19,4)
│ password_hash │       │ currency      │    never float/double
│ first_name    │       │ is_active     │
│ last_name     │       │ version       │◄── optimistic lock
│ stripe_cust_id│       │ created_at    │
│ is_enabled    │       │ updated_at    │
│ created_at    │       └───────┬───────┘
│ updated_at    │               │
└───────────────┘           1:N │
                                │
                    ┌───────────▼────────────┐
                    │      Transaction        │
                    │────────────────────────│
                    │ id (UUID, PK)           │
                    │ from_wallet_id (FK)     │◄── null for DEPOSIT
                    │ to_wallet_id (FK)       │◄── null for WITHDRAWAL
                    │ amount                  │
                    │ currency                │
                    │ type (DEPOSIT/TRANSFER/ │
                    │       WITHDRAWAL)       │
                    │ status (PENDING/        │
                    │   COMPLETED/FAILED/     │
                    │   REVERSED)             │
                    │ stripe_payment_intent_id│
                    │ idempotency_key         │◄── unique index
                    │ description             │
                    │ failure_reason          │
                    │ created_at              │
                    │ updated_at              │
                    └─────────────────────────┘

┌──────────────────────┐       ┌─────────────────────┐
│    IdempotencyKey    │       │    RefreshToken      │
│──────────────────────│       │─────────────────────│
│ id (UUID, PK)        │       │ id (UUID, PK)        │
│ key_hash (SHA-256)   │◄──┐   │ token (512)          │
│ user_id              │   │   │ user_id (FK)         │
│ endpoint             │   │   │ expires_at           │
│ response_body (TEXT) │   │   │ is_revoked           │
│ response_status      │   │   │ created_at           │
│ expires_at           │   │   └─────────────────────┘
│ created_at           │   │
└──────────────────────┘   └── unique(key_hash, user_id)
```

### 4.2 Key Design Decisions

**BigDecimal for money** — all monetary amounts use `BigDecimal(19,4)`. Using `double` or `float` for currency is a well-known source of rounding bugs (e.g. `0.1 + 0.2 ≠ 0.3` in IEEE 754).

**Optimistic + Pessimistic locking** — `Wallet` carries a `@Version` field for optimistic locking (catches stale reads) and the repository has `PESSIMISTIC_WRITE` lock queries for operations that must atomically read-modify-write the balance.

**Soft-delete pattern for users** — `is_enabled` flag disables accounts without deleting data or breaking foreign keys on transactions.

---

## 5. API Reference

### 5.1 Authentication Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/auth/register` | None | Create account |
| POST | `/api/auth/login` | None | Issue JWT tokens |
| POST | `/api/auth/refresh` | None | Rotate refresh token |
| POST | `/api/auth/logout` | JWT | Revoke all refresh tokens |

### 5.2 Wallet Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/wallet` | JWT | Get balance and currency |
| GET | `/api/wallet/transactions` | JWT | Paginated transaction history |

### 5.3 Payment Endpoints

| Method | Path | Auth | Headers | Description |
|--------|------|------|---------|-------------|
| POST | `/api/payments/deposit` | JWT | `X-Idempotency-Key` | Create Stripe PaymentIntent |
| POST | `/api/payments/send` | JWT | `X-Idempotency-Key` | P2P transfer |
| GET | `/api/payments/{id}` | JWT | — | Get transaction detail |

### 5.4 Webhook Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/webhooks/stripe` | Stripe-Signature | Receive Stripe events |

---

## 6. Key Flows

### 6.1 Add Funds (Deposit)

```
Client                     Backend                      Stripe
  │                           │                           │
  │  POST /payments/deposit   │                           │
  │  X-Idempotency-Key: uuid  │                           │
  │──────────────────────────►│                           │
  │                           │  Check idempotency store  │
  │                           │  Rate limit check         │
  │                           │  PaymentIntent.create()──►│
  │                           │◄─────────────── intent ───│
  │                           │  Save PENDING transaction │
  │◄──────────────────────────│                           │
  │  { clientSecret, intentId}│                           │
  │                           │                           │
  │  Stripe.js confirmCard()──┼───────────────────────────►
  │                           │                           │
  │                           │◄── POST /webhooks/stripe ─│
  │                           │    payment_intent.succeeded
  │                           │  Verify HMAC-SHA256 sig   │
  │                           │  PESSIMISTIC_WRITE lock   │
  │                           │  wallet.balance += amount │
  │                           │  txn.status = COMPLETED   │
```

### 6.2 Send Money (P2P Transfer)

```
Client                     Backend
  │                           │
  │  POST /payments/send      │
  │  X-Idempotency-Key: uuid  │
  │──────────────────────────►│
  │                           │  Validate idempotency key
  │                           │  Rate limit check
  │                           │  Resolve sender & recipient
  │                           │  ┌─────────────────────┐
  │                           │  │  DB Transaction      │
  │                           │  │  LOCK sender wallet  │◄── PESSIMISTIC_WRITE
  │                           │  │  LOCK recip wallet   │◄── PESSIMISTIC_WRITE
  │                           │  │  Check balance ≥ amt │
  │                           │  │  sender -= amount    │
  │                           │  │  recip  += amount    │
  │                           │  │  Save Transaction    │
  │                           │  └─────────────────────┘
  │◄──────────────────────────│
  │  { txnId, status, amount }│
```

### 6.3 Authentication Flow

```
                    ┌─────────────────────────────────────────────┐
                    │              Login / Register                │
                    │                                             │
                    │  1. Validate credentials                    │
                    │  2. Generate JWT (15 min, HMAC-SHA256)      │
                    │  3. Generate RefreshToken (64 random bytes) │
                    │     → stored in DB with expiresAt           │
                    └──────────────────┬──────────────────────────┘
                                       │
                         ┌─────────────▼──────────────┐
                         │   Access Token (15 min)    │
                         │   stateless, no DB hit     │
                         │   verified by signature    │
                         └─────────────┬──────────────┘
                                       │ expires
                         ┌─────────────▼──────────────┐
                         │  POST /auth/refresh         │
                         │  1. Look up token in DB     │
                         │  2. Verify not revoked      │
                         │  3. Verify not expired      │
                         │  4. Revoke ALL old tokens   │ ◄── detects theft
                         │  5. Issue new pair          │
                         └────────────────────────────┘
```

---

## 7. Security Design

### 7.1 Threat Model

| Threat | Mitigation |
|--------|-----------|
| Password brute-force | BCrypt cost factor 12; timing-safe comparison |
| JWT forgery | HMAC-SHA256 signature with server-side secret |
| Stolen access token | 15-min expiry limits damage window |
| Stolen refresh token | DB-backed rotation — reuse detected and all sessions revoked |
| Double-spend race | Pessimistic DB locks on both wallets in one transaction |
| Idempotency replay | SHA-256(userId + key) — cross-user replay impossible |
| Webhook spoofing | Stripe HMAC-SHA256 signature verified before any processing |
| API abuse | Sliding-window rate limiter: 10 req/min per user per endpoint |
| SQL injection | JPA parameterized queries — no raw SQL string concatenation |
| XSS via response | `Content-Security-Policy: default-src 'self'` header |
| Clickjacking | `X-Frame-Options: SAMEORIGIN` |
| User enumeration | Identical error message for "user not found" and "wrong password" |
| Overly long inputs | Bean Validation on all request bodies; idempotency key length cap |

### 7.2 Idempotency Key Design

```
Raw key from client header
         │
         ▼
SHA-256(userId + ":" + rawKey)
         │
         ▼
Stored as 64-char hex in idempotency_keys table
with unique(key_hash, user_id) constraint

Why hash? → Prevents storing client-controlled strings directly in DB
Why include userId? → Prevents User A replaying User B's key
TTL = 24h → Cleaned up by @Scheduled hourly task
```

### 7.3 Wallet Locking Strategy

P2P transfers acquire locks in a consistent order to prevent deadlock:

```java
// Both locks acquired inside a single @Transactional
Wallet senderWallet   = walletRepository.findByUserIdWithLock(senderId);
Wallet recipientWallet = walletRepository.findByUserIdWithLock(recipientId);
```

The `@Version` field on `Wallet` provides a second safety net via optimistic locking — a concurrent update that slips past the pessimistic lock will be caught by Hibernate and retried.

---

## 8. Package Structure

```
com.payment.service/
├── PaymentServiceApplication.java
│
├── config/
│   ├── SecurityConfig.java        # Spring Security filter chain, CORS, headers
│   └── StripeConfig.java          # Stripe SDK init, exposes webhook secret
│
├── controller/
│   ├── AuthController.java        # /api/auth/**
│   ├── WalletController.java      # /api/wallet/**
│   ├── PaymentController.java     # /api/payments/**
│   └── WebhookController.java     # /api/webhooks/stripe
│
├── service/
│   ├── AuthService.java           # Register, login, token lifecycle
│   ├── WalletService.java         # Balance reads, transaction history
│   ├── PaymentService.java        # Stripe deposit intent, P2P transfer
│   ├── IdempotencyService.java    # Key hashing, storage, lookup, purge
│   ├── RefreshTokenService.java   # Token creation, validation, rotation
│   └── RateLimiterService.java    # Sliding-window in-memory limiter
│
├── security/
│   ├── JwtTokenProvider.java      # Generate and validate JWTs
│   ├── JwtAuthenticationFilter.java  # OncePerRequestFilter
│   └── UserDetailsServiceImpl.java   # Loads user by UUID for Spring Security
│
├── model/
│   ├── User.java
│   ├── Wallet.java
│   ├── Transaction.java
│   ├── IdempotencyKey.java
│   ├── RefreshToken.java
│   └── enums/
│       ├── Role.java
│       ├── TransactionType.java
│       └── TransactionStatus.java
│
├── repository/
│   ├── UserRepository.java
│   ├── WalletRepository.java      # Includes PESSIMISTIC_WRITE lock queries
│   ├── TransactionRepository.java
│   ├── IdempotencyKeyRepository.java
│   └── RefreshTokenRepository.java
│
├── dto/
│   ├── request/
│   │   ├── RegisterRequest.java
│   │   ├── LoginRequest.java
│   │   ├── RefreshTokenRequest.java
│   │   ├── AddFundsRequest.java
│   │   ├── SendMoneyRequest.java
│   │   └── RequestMoneyRequest.java
│   └── response/
│       ├── ApiResponse.java        # Uniform envelope for all responses
│       ├── AuthResponse.java
│       ├── UserResponse.java
│       ├── WalletResponse.java
│       ├── TransactionResponse.java
│       └── PaymentIntentResponse.java
│
└── exception/
    ├── GlobalExceptionHandler.java
    ├── PaymentException.java
    ├── InsufficientFundsException.java
    ├── IdempotencyConflictException.java
    ├── ResourceNotFoundException.java
    └── RateLimitException.java
```

---

## 9. Error Handling

All errors are returned in a consistent envelope:

```json
{
  "success": false,
  "message": "Insufficient balance. Available: 45.00",
  "data": null,
  "timestamp": "2026-06-16T10:00:00Z"
}
```

| Exception | HTTP Status | Scenario |
|-----------|-------------|----------|
| `MethodArgumentNotValidException` | 400 | Bean Validation failure |
| `PaymentException` | 400 | Business rule violation |
| `BadCredentialsException` | 401 | Wrong email or password |
| `SecurityException` | 401 | Expired or revoked refresh token |
| `AccessDeniedException` | 403 | Accessing another user's resource |
| `ResourceNotFoundException` | 404 | User, wallet, or transaction not found |
| `InsufficientFundsException` | 422 | Balance too low for transfer |
| `IdempotencyConflictException` | 409 | Concurrent duplicate request |
| `RateLimitException` | 429 | Too many requests |
| `Exception` (catch-all) | 500 | Unexpected error — details hidden from client |

---

## 10. Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `stripe.secret-key` | env: `STRIPE_SECRET_KEY` | Stripe API secret |
| `stripe.webhook-secret` | env: `STRIPE_WEBHOOK_SECRET` | Webhook HMAC secret |
| `jwt.secret` | env: `JWT_SECRET` | Base64 256-bit signing key |
| `jwt.access-token-expiration` | `900000` ms (15 min) | Access token TTL |
| `jwt.refresh-token-expiration` | `604800000` ms (7 days) | Refresh token TTL |
| `payment.max-transfer-amount` | `10000.00` | Per-transfer cap |
| `payment.min-transfer-amount` | `0.50` | Stripe minimum |
| `payment.idempotency-key-ttl-hours` | `24` | Idempotency key retention |
| `payment.rate-limit.requests-per-minute` | `10` | Requests per user per endpoint |

---

## 11. Production Readiness Checklist

### Must-Have Before Go-Live
- [ ] Replace H2 with PostgreSQL (`spring.profiles.active=prod`)
- [ ] Store `JWT_SECRET`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` in a secrets manager (AWS Secrets Manager / HashiCorp Vault)
- [ ] Enable HTTPS only (terminate TLS at load balancer or configure Spring SSL)
- [ ] Replace in-memory rate limiter with Redis-backed implementation
- [ ] Replace in-memory idempotency store with Redis for multi-instance deployments
- [ ] Add Stripe Connect for real bank payouts/withdrawals

### Recommended
- [ ] Distributed tracing (e.g. OpenTelemetry + Jaeger) — add `traceId` to log lines
- [ ] Metrics endpoint (Micrometer + Prometheus)
- [ ] Email/SMS notifications on transfer events
- [ ] KYC/AML compliance layer for regulatory requirements
- [ ] 2FA for high-value transactions
- [ ] Audit log table (immutable append-only record of all balance changes)
