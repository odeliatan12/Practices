# 05 — Authentication & Security
### Scale target: Shopee / Lazada — 50M+ daily active users, Southeast Asia multi-country

---

## Architecture Overview

```
Client (Web / iOS / Android / Mini-Program)
        │
        ▼
   CDN + WAF (CloudFlare / AWS Shield)       ← DDoS, bot filtering, geo-blocking
        │
        ▼
   API Gateway (Kong / AWS API GW)           ← rate limiting, JWT validation, routing
        │
        ▼
   Auth Service (dedicated microservice)     ← login, token issuance, refresh, logout
        │
   ┌────┴────────────────────┐
   │  Redis Cluster           │             ← refresh token store, token blacklist,
   │  (session data, OTP,     │                rate limit counters, device sessions
   │   rate limit counters)   │
   └──────────────────────────┘
        │
   Internal Services (Order, Inventory, Payment…)
        │
   Kafka Audit Topic                        ← all auth events streamed for fraud analysis
```

---

## 1. Authentication Strategy

### Multiple login methods (Shopee/Lazada support all of these)

| Method | When used |
|---|---|
| Email + Password | Web, desktop |
| Phone + OTP | Primary in SEA — most users register with phone |
| Social Login (Google, Facebook, Apple, LINE, WeChat) | Mobile apps |
| Seller portal SSO | Merchant dashboard |
| Admin SSO (Okta / Azure AD) | Internal staff |

### Token strategy — JWT with Redis-backed refresh tokens

- **Access token** — JWT, 15 minutes, stateless
  - Verified by every downstream service using the public key
  - Contains: `userId`, `roles`, `deviceId`, `countryCode`

- **Refresh token** — opaque UUID, 30 days (mobile) / 7 days (web)
  - Stored in **Redis** (not the database) — must survive 50M concurrent sessions
  - Keyed by `userId:deviceId` — one token per device per user
  - Redis TTL auto-expires stale tokens with no cron job needed

- **Why Redis instead of DB for refresh tokens at scale:**
  ```
  50M users × avg 2 devices = 100M active refresh token lookups/day
  DB lookup: ~5ms  → 100M × 5ms = 500,000 CPU seconds/day
  Redis lookup: ~0.1ms → 100M × 0.1ms = 10,000 CPU seconds/day
  50x faster — critical at Shopee/Lazada scale
  ```

---

## 2. Token Flow

### Login (phone + OTP — primary SEA flow)
```
POST /auth/otp/send
Body: { phone: "+6591234567", countryCode: "SG" }
→ OTP generated, stored in Redis with 5-minute TTL
→ SMS sent via Twilio / SNS

POST /auth/otp/verify
Body: { phone: "+6591234567", otp: "123456", deviceId: "uuid", deviceName: "iPhone 15" }
→ OTP validated and deleted from Redis (single use)
→ Returns: accessToken (body) + refreshToken (httpOnly cookie or response body for mobile)
```

### Login (email + password flow)
```
POST /auth/login
Body: { email, password, deviceId, deviceFingerprint }
→ BCrypt verify
→ Check: account locked? suspicious device? new country?
→ If risk score high → require OTP step-up
→ Returns: accessToken + refreshToken
```

### Authenticated Request
```
GET /api/orders
Header: Authorization: Bearer <accessToken>
        X-Device-Id: <deviceId>
        X-Country: SG

Gateway:
  1. Validates JWT signature (RS256 public key)
  2. Checks token not in Redis blacklist (for forced logout)
  3. Adds X-User-Id, X-User-Roles, X-Country to forwarded request
  4. Downstream services trust these headers — never re-validate JWT
```

### Token Refresh (with rotation + reuse detection)
```
POST /auth/refresh
Cookie/Body: refreshToken=<token>

1. Look up token in Redis
2. REUSE DETECTION: if token already used once → family compromised
   → invalidate ALL tokens for this user across ALL devices
   → force re-login everywhere
   → alert fraud team
3. If valid → delete old token, issue new refresh + access token
4. Store new token in Redis with TTL reset
```

### Forced Logout (admin / fraud team use)
```
POST /auth/logout/all
Header: Authorization: Bearer <adminToken>
Body: { userId: "uuid", reason: "account_compromised" }

→ Delete all refresh tokens for user from Redis (pattern: userId:*)
→ Add userId to JWT blacklist in Redis (TTL = access token lifetime = 15m)
→ All existing access tokens rejected at gateway within 15 minutes
```

---

## 3. JWT Structure

### Header
```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "key-2024-06"
}
```
`kid` (key ID) enables **zero-downtime key rotation** — gateway checks which public key to use.

### Payload (Claims)
```json
{
  "sub": "user-uuid",
  "email": "buyer@example.com",
  "roles": ["ROLE_BUYER"],
  "sellerId": null,
  "deviceId": "device-uuid",
  "countryCode": "SG",
  "sessionId": "session-uuid",
  "iat": 1717459200,
  "exp": 1717460100,
  "jti": "unique-token-id"
}
```

- `jti` — unique token ID, used for blacklisting without storing the full token
- `kid` — which signing key was used, supports key rotation
- `deviceId` — enables per-device revocation
- `sessionId` — enables per-session revocation (logout one tab without affecting others)

Use **RS256** (asymmetric) — private key signs (Auth Service only), public key verifies (all services). Never share the private key.

---

## 4. Roles and Multi-Tenant Authorization

### Roles
| Role | Description |
|---|---|
| `ROLE_BUYER` | Browse, order, track, review |
| `ROLE_SELLER` | Manage listings, view their own orders, process shipments |
| `ROLE_SELLER_ADMIN` | Full access to a seller's account (multi-user seller teams) |
| `ROLE_WAREHOUSE` | Update fulfillment status |
| `ROLE_FINANCE` | View payment data, process refunds |
| `ROLE_CS_AGENT` | Customer service — read-only + dispute management |
| `ROLE_FRAUD_ANALYST` | Read access to flagged accounts and transactions |
| `ROLE_ADMIN` | Full platform access |

### RBAC vs ABAC

For Shopee/Lazada scale, **ABAC (Attribute-Based Access Control)** is used alongside RBAC:

```java
// RBAC — role check only (simple)
@PreAuthorize("hasRole('SELLER')")
public void updateListing(UUID listingId) { ... }

// ABAC — role + ownership + business rule (Shopee-style)
@PreAuthorize("""
    hasRole('SELLER')
    and @listingSecurityService.isOwner(#listingId, authentication.name)
    and @listingSecurityService.isNotDuringFlashSale(#listingId)
""")
public void updateListingPrice(UUID listingId, BigDecimal newPrice) { ... }
```

### Resource-Level Rules
- Buyers can only read/cancel **their own** orders (`customerId == jwtSub`)
- Sellers can only view orders for **their own products** (`sellerId == jwtSellerId`)
- CS agents can read any order but cannot modify financial data
- Cross-country access blocked — SG seller cannot see MY buyer data (data residency)

---

## 5. Rate Limiting (critical at Shopee scale)

```
Login endpoint:      5 attempts per phone/email per 15 minutes
OTP send:            3 OTPs per phone per 10 minutes
OTP verify:          5 wrong attempts → lock OTP for 10 minutes
API (authenticated): 1000 requests/minute per user
API (anonymous):     60 requests/minute per IP
Flash sale endpoint: 500 requests/second per product (dedicated limit)
```

Implemented in **Redis** using sliding window counters — not in-memory (survives pod restarts, shared across pods):

```java
// Redis sliding window rate limiter
String key = "rate_limit:login:" + phoneNumber;
Long count = redisTemplate.opsForValue().increment(key);
if (count == 1) redisTemplate.expire(key, 15, TimeUnit.MINUTES);
if (count > 5) throw new RateLimitExceededException();
```

---

## 6. Device Trust and Fraud Detection

### New device detection
```
First login from new device:
  → send email/SMS "New device login detected"
  → optionally require OTP step-up for high-value actions

Impossible travel:
  Login from Singapore at 10:00
  Login from UK at 10:05
  → flag as suspicious
  → require re-authentication
  → alert fraud team
```

### Device fingerprint signals collected at login
```json
{
  "deviceId":   "persisted UUID stored in device storage",
  "userAgent":  "Mozilla/5.0...",
  "screenRes":  "1920x1080",
  "timezone":   "Asia/Singapore",
  "language":   "en-SG",
  "ip":         "123.456.789.0",
  "country":    "SG"
}
```

### Risk scoring
```
Risk score = 0 (safe) to 100 (block)

score += 30  if new device
score += 40  if new country
score += 20  if IP on fraud list
score += 50  if impossible travel detected
score -= 10  if trusted device (seen > 30 days)

score >= 70  → require OTP step-up
score >= 90  → block, alert fraud team
```

---

## 7. Flash Sale Security (concurrency specific to Shopee/Lazada)

Flash sales create the same race conditions as our inventory service, but at 100x the scale.

```java
// Flash sale endpoint — additional security layers
@RateLimiter(key = "flash_sale:{productId}", limit = 500, window = 1, unit = SECONDS)
@PreAuthorize("hasRole('BUYER') and @flashSaleService.isEligible(#userId, #saleId)")
@PostMapping("/flash-sale/{saleId}/purchase")
public PurchaseResult purchase(@PathVariable UUID saleId,
                               @AuthenticationPrincipal JwtUser user) {
    // eligibility: one purchase per user per flash sale
    // atomic reserve: same pattern as your inventory adjustIfNonNegative()
    return flashSaleService.atomicPurchase(saleId, user.getId());
}
```

Eligibility check prevents the same user from using multiple devices/accounts to buy the same item — a common abuse pattern on Shopee.

---

## 8. Password Security

- Passwords hashed with **bcrypt** (cost factor 12)
- Minimum 8 characters, at least one number and one special character
- Brute force protection: lock account after 5 failed attempts for 15 minutes (Redis counter)
- Password reset via time-limited signed token (expires 1 hour, single use)
- **Credential stuffing protection** — check against HaveIBeenPwned API on registration
- Sellers (higher value accounts) — MFA enforced, cannot disable

---

## 9. API Security

### Input Validation
- All request bodies validated with Bean Validation (`@Valid`)
- Reject requests with unexpected fields (`@JsonIgnoreProperties(allowSetters = false)`)
- Validate UUID format on all path variables
- Max payload size enforced at gateway (e.g. 10MB for uploads, 64KB for JSON)

### SQL Injection Prevention
- JPA/Hibernate parameterised queries only — no string concatenation in queries
- JPQL and native queries use named parameters (`@Param`)
- Flyway-managed schema — no dynamic DDL at runtime

### XSS Prevention
- API is JSON-only — no HTML rendering in backend
- `Content-Type: application/json` enforced on all responses
- Seller product descriptions sanitised with OWASP Java HTML Sanitizer before storage
- Frontend: React escapes by default; avoid `dangerouslySetInnerHTML`

### CSRF
- Not needed for stateless JWT API with `Authorization` header
- Seller admin portal uses SameSite=Strict cookies as additional protection

### CORS
- Configured at API Gateway level (not per-service)
- Allow only known frontend origins — never `*` in production
- Credentials (cookies) allowed only for the specific allowed origin

---

## 10. Transport Security

- HTTPS everywhere (TLS 1.3, minimum TLS 1.2)
- HTTP Strict Transport Security (HSTS) with `max-age=31536000; includeSubDomains`
- Certificates — AWS ACM or Let's Encrypt, auto-renewed
- **mTLS** for all internal service-to-service calls inside Kubernetes cluster
- **Service mesh (Istio)** — enforces mTLS, handles certificate rotation automatically
- No service can call another without a valid certificate — zero trust networking

---

## 11. Secret Management

- Database passwords, API keys, JWT private key → **AWS Secrets Manager** or **HashiCorp Vault**
- Secrets injected at pod startup — never baked into Docker images
- Never committed to git (`.gitignore` + git-secrets pre-commit hook)
- Local dev — `.env` file (in `.gitignore`)
- **Key rotation** — RS256 key pair rotated every 90 days
  - New `kid` issued, old key kept valid for 15 minutes (current access token lifetime)
  - Gateway fetches public keys from JWKS endpoint — rotation is zero-downtime

---

## 12. Security Headers

Set on all API Gateway responses:
```
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'
Permissions-Policy: geolocation=(), camera=(), microphone=()
Cache-Control: no-store                         (on all auth endpoints)
X-Request-Id: <uuid>                            (for distributed tracing)
```

---

## 13. Audit Logging at Scale

All auth and sensitive events streamed to **Kafka** topic `security-audit`:

```json
{
  "eventType":  "LOGIN_SUCCESS",
  "userId":     "uuid",
  "deviceId":   "uuid",
  "ip":         "123.456.789.0",
  "country":    "SG",
  "riskScore":  15,
  "timestamp":  "2024-06-10T10:00:00Z",
  "traceId":    "distributed-trace-uuid"
}
```

Event types logged:
- `LOGIN_SUCCESS`, `LOGIN_FAILED`, `ACCOUNT_LOCKED`
- `OTP_SENT`, `OTP_VERIFIED`, `OTP_FAILED`
- `TOKEN_REFRESHED`, `TOKEN_REUSE_DETECTED`
- `LOGOUT`, `FORCED_LOGOUT_ALL`
- `PASSWORD_RESET_REQUESTED`, `PASSWORD_CHANGED`
- `NEW_DEVICE_LOGIN`, `IMPOSSIBLE_TRAVEL_DETECTED`
- `RATE_LIMIT_EXCEEDED`, `FRAUD_FLAG_RAISED`

Downstream consumers:
- **ELK stack** — search and dashboards for security team
- **Fraud ML pipeline** — real-time anomaly detection
- **Compliance reports** — PDPA (SG), PDPL (TH), PDP (ID) regulatory requirements

---

## 14. Regional Compliance

| Country | Regulation | Key requirement |
|---|---|---|
| Singapore | PDPA | Data stored in SG region, breach notification within 3 days |
| Thailand | PDPA TH | Explicit consent for marketing, right to erasure |
| Indonesia | PDP Law | Local data residency for citizen data |
| Malaysia | PDPA MY | Cannot transfer data outside without consent |
| Philippines | Data Privacy Act | Data Protection Officer required |

Implementation:
- User data tagged with `countryCode` at creation
- Data residency enforced at database level — SG users in SG region DB, MY users in MY region DB
- Right to erasure — anonymise PII on request, retain transaction records (legal hold)
- Consent management service — tracks what data each user has consented to share

---

## 15. Dependency and Container Security

```bash
# Scan dependencies for CVEs on every build
mvn dependency-check:check

# Scan Docker images before deployment
trivy image order-service:latest

# SAST — static analysis in CI pipeline
semgrep --config=auto src/

# Check for secrets accidentally committed
git-secrets --scan
trufflehog git file://. --only-verified
```

CI pipeline blocks deployment if:
- Any CRITICAL severity CVE found in dependencies
- Any secret detected in committed code
- SAST finds SQL injection or XSS risk
