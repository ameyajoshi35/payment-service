# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
mvn clean compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=PaymentServiceTest

# Run a single test method
mvn test -Dtest=PaymentServiceTest#sendMoney_success_debitsAndCreditsCorrectly

# Run the application (dev profile with H2)
mvn spring-boot:run

# Run with prod profile (requires PostgreSQL + env vars)
SPRING_PROFILES_ACTIVE=prod \
  STRIPE_SECRET_KEY=sk_... \
  STRIPE_WEBHOOK_SECRET=whsec_... \
  JWT_SECRET=<64-char-hex> \
  mvn spring-boot:run
```

## Architecture

**Package layout:** `com.payment.service.{model,repository,service,controller,security,config,exception,dto}`

**Request lifecycle:**
1. `JwtAuthenticationFilter` extracts Bearer token → validates → loads `UserDetails` by UUID string (NOT email) → sets `SecurityContext`
2. Controller calls `RateLimiterService.checkRateLimit(userId, endpoint)` first
3. Controller calls `IdempotencyService.findExistingResult(key, userId)` — returns cached response on duplicate
4. Business logic executes; result stored via `IdempotencyService.storeResult()`

**Critical design decisions requiring cross-file understanding:**

- **JWT subject = userId (UUID string), not email.** `JwtTokenProvider` embeds userId as `sub`, email as a claim. `UserDetailsServiceImpl.loadUserByUsername()` receives the UUID string. Controllers call `UUID.fromString(authentication.getName())` to get the user ID.

- **Pessimistic locking for wallet mutations.** `WalletRepository` has two `@Lock(PESSIMISTIC_WRITE)` queries. `PaymentService.sendMoney()` acquires locks on BOTH wallets inside a single `@Transactional` — deadlock risk is managed by always locking sender-first.

- **Idempotency key hashing.** `IdempotencyService.computeHash()` = `SHA-256(userId + ":" + rawKey)`. This means the same raw key from two different users produces different hashes, preventing cross-user replay.

- **Stripe deposit is async (2-step).** `createDepositIntent()` creates a PENDING transaction immediately. `WebhookController` handles `payment_intent.succeeded` / `payment_intent.payment_failed` to credit wallet or mark failed. `handleDepositSuccess()` is idempotent — checks `COMPLETED` status before crediting.

- **Refresh token rotation.** `AuthService.refreshToken()` revokes ALL tokens for the user before issuing a new pair (via `revokeAllUserTokens()`). One-time-use enforced at DB level.

- **No Lombok.** Removed due to JDK 26 incompatibility (`ExceptionInInitializerError` on `TypeTag :: UNKNOWN`). All entities have explicit getters/setters and constructors.

## Testing

Tests use Mockito with Byte Buddy experimental mode (configured in `pom.xml` Surefire `<argLine>`) — required for JDK 26 support.

Controller tests (`@WebMvcTest`) must declare `@MockBean JwtTokenProvider jwtTokenProvider` because `JwtAuthenticationFilter` is a `@Component` picked up by the web slice. `TestSecurityConfig` (in `controller/` test package) disables real JWT security for all web slice tests.

`PaymentServiceTest` uses `ReflectionTestUtils.setField()` to inject `maxTransferAmount` / `minTransferAmount` (normally injected via `@Value`).

## Key configuration properties

| Property | Default | Source |
|---|---|---|
| `jwt.secret` | — | `JWT_SECRET` env var |
| `jwt.access-token-expiration` | 900000ms | `application.yml` |
| `stripe.secret-key` | — | `STRIPE_SECRET_KEY` env var |
| `payment.max-transfer-amount` | 10000.00 | `application.yml` |
| `payment.min-transfer-amount` | 0.50 | `application.yml` |
| `rate-limit.max-requests` | 10 | `application.yml` |
| `rate-limit.window-seconds` | 60 | `application.yml` |
