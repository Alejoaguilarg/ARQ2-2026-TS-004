# Spec Delta

## Purpose

Manages robust authentication and financial session authorization for P2P transfers, including short-lived access tokens with refresh-token rotation, device binding, step-up authorization (PIN/OTP) for transfers, and revocation with rate limiting at the Gateway.

## ADDED Requirements

### Requirement: Short-lived access tokens with refresh-token rotation
The Identity Service SHALL issue JWT access tokens (15 min TTL) and rotating refresh tokens (7 day TTL, single-use) upon successful login.

#### Scenario: Login returns access and refresh tokens
- **WHEN** user authenticates with valid credentials
- **THEN** Identity Service returns access_token (15 min) and refresh_token (7 days, rotating)

#### Scenario: Refresh token rotation on use
- **WHEN** client uses refresh token to obtain new access token
- **THEN** Identity Service invalidates used refresh token, issues new access token + new refresh token

#### Scenario: Reused refresh token triggers revocation
- **WHEN** same refresh token presented twice
- **THEN** Identity Service revokes all user tokens, forces re-login, audit event recorded

### Requirement: Device binding for tokens
The Identity Service SHALL bind tokens to device fingerprint (user agent, IP subnet, secure hardware signal) and reject tokens from unrecognized devices.

#### Scenario: Token from new device rejected
- **WHEN** access token presented from device not registered at issuance
- **THEN** Gateway returns 401 Unauthorized, requires re-authentication

### Requirement: Financial session step-up authorization for transfers
The Identity Service SHALL require a financial session (step-up authorization via PIN or OTP) for POST /transfers, issuing a short-lived (5 min), single-purpose financial-session token bound to user, device, and transfer amount.

#### Scenario: Transfer requires financial session token
- **WHEN** client calls POST /transfers without financial-session header
- **THEN** Gateway returns 403 Forbidden with error code FINANCIAL_SESSION_REQUIRED

#### Scenario: Financial session created via PIN/OTP
- **WHEN** user completes step-up challenge (PIN entry or OTP verification)
- **THEN** Identity Service issues financial-session token (5 min TTL, single-use, bound to amount)

#### Scenario: Financial session token consumed on transfer
- **WHEN** POST /transfers succeeds with valid financial-session token
- **THEN** token invalidated, cannot be reused for another transfer

### Requirement: Token revocation via Redis with sub-millisecond check
The Identity Service SHALL maintain a revocation list in Redis (token JTI → revoked timestamp) checked by Gateway on every request.

#### Scenario: Revoked token rejected immediately
- **WHEN** access token JTI exists in Redis revocation set
- **THEN** Gateway returns 401 Unauthorized on next request

#### Scenario: Logout revokes all user tokens
- **WHEN** user logs out or security event triggers revocation
- **THEN** all user tokens added to Redis revocation set, subsequent requests rejected

### Requirement: Rate limiting and lockout at Gateway
The API Gateway SHALL enforce rate limits on auth endpoints (login, refresh, step-up) and lockout after 5 failed attempts within 15 minutes.

#### Scenario: Excessive login attempts locked out
- **WHEN** 5 failed login attempts for same user within 15 minutes
- **THEN** Gateway returns 429 Too Many Requests, lockout for 30 minutes