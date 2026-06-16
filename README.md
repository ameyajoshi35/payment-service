# Payment Service

A RESTful P2P payment service built on Spring Boot 3.2 with Stripe-funded wallets, JWT auth, idempotent transfers, and pessimistic locking for safe concurrent debits/credits.

See [DESIGN.md](DESIGN.md) for the full design document.

## Stack

- Java 21, Spring Boot 3.2.4 (Web, Security, Data JPA, Validation)
- H2 (dev) / PostgreSQL (prod)
- JJWT 0.12 for access + refresh tokens
- Stripe Java SDK 24.3 for card funding via PaymentIntents
- JUnit 5 + Mockito for tests

## Features

- Email/password registration with BCrypt hashing
- Stateless JWT auth — short-lived access tokens, rotating refresh tokens
- Wallet-to-wallet transfers with pessimistic DB locks (sender-locked-first to avoid deadlock)
- Stripe-funded deposits via PaymentIntent + webhook-driven wallet credit
- Idempotency keys (`SHA-256(userId + ":" + rawKey)`, 24h TTL) prevent duplicate charges and cross-user replay
- Per-user, per-endpoint sliding-window rate limiting
- `BigDecimal(19,4)` for money — never float/double

## Quick start

```bash
# Build
mvn clean compile

# Run tests
mvn test

# Run locally (dev profile, H2, no external deps)
mvn spring-boot:run

# Run with prod profile (PostgreSQL + real Stripe + secrets)
SPRING_PROFILES_ACTIVE=prod \
  STRIPE_SECRET_KEY=sk_... \
  STRIPE_WEBHOOK_SECRET=whsec_... \
  JWT_SECRET=<64-char-hex> \
  DATABASE_URL=jdbc:postgresql://... \
  DATABASE_USERNAME=... \
  DATABASE_PASSWORD=... \
  mvn spring-boot:run
```

## API surface

| Method | Path                          | Auth | Purpose                                  |
|--------|-------------------------------|------|------------------------------------------|
| POST   | `/api/auth/register`          | No   | Create account, return tokens            |
| POST   | `/api/auth/login`             | No   | Exchange credentials for tokens          |
| POST   | `/api/auth/refresh`           | No   | Rotate refresh token, issue new pair     |
| POST   | `/api/auth/logout`            | Yes  | Revoke all refresh tokens for user       |
| GET    | `/api/wallet`                 | Yes  | Current balance                          |
| GET    | `/api/wallet/transactions`    | Yes  | Paginated transaction history            |
| POST   | `/api/payments/send`          | Yes  | P2P transfer (idempotency key required)  |
| POST   | `/api/payments/deposit`       | Yes  | Create Stripe PaymentIntent              |
| POST   | `/api/webhooks/stripe`        | No*  | Stripe webhook (signature-verified)      |

\* Webhook endpoint is publicly reachable but validates Stripe's signature header.

## Project layout

```
src/main/java/com/payment/service/
├── config/        Spring & Stripe configuration
├── controller/    REST endpoints
├── dto/           Request/response payloads
├── exception/     Domain exceptions + GlobalExceptionHandler
├── model/         JPA entities (User, Wallet, Transaction, ...)
├── repository/    Spring Data JPA repositories
├── security/      JWT filter, token provider, UserDetails service
└── service/       Business logic (Auth, Wallet, Payment, Idempotency, RateLimiter)
```

## Notes

- JWT `sub` is the user's UUID, **not** their email — controllers do `UUID.fromString(authentication.getName())`.
- Stripe deposits are two-step: `POST /deposit` creates a PENDING transaction; the wallet is credited only when the `payment_intent.succeeded` webhook arrives. `handleDepositSuccess()` is idempotent.
- No Lombok — removed because of a JDK 26 incompatibility (`TypeTag :: UNKNOWN`). All entities have explicit getters, setters, and constructors.
