# Identity Service

Users, sessions and the step-up that every transfer needs (spec §3.3 to §3.6, §3.12, Feature 1). It signs the access and financial-session tokens, publishes the public keys the Gateway verifies them with, and resolves a recipient's email to a `userId`.
Java 21, Spring Boot 3.4, Bancolombia clean-architecture scaffold, PostgreSQL 16 (`identity_db`), Redis.

## What it does

| Endpoint | Does |
|---|---|
| `POST /auth/login` | Checks the argon2 password hash, registers the device on first use, opens a session: a 15-minute RS256 access token (`sub`, `did`, `sid`, `jti`, `roles`) and a 7-day refresh token bound to the device (F1.1) |
| `POST /auth/refresh` | Rotates the refresh token inside its session (F1.2). A rotated token presented again revokes the whole session, in `identity_db` and in Redis (F1.3); a revoked session never comes back |
| `POST /auth/logout` | Revokes the session and the access token's `jti` in Redis (F1.4) |
| `POST /auth/financial-session` | Verifies the PIN (argon2) or a TOTP code (RFC 6238) and issues a 5-minute token with scope `transfer:create`, bound to the user and device (F1.7) |
| `POST /users/lookup` | `{email}` → `{userId}` of an active or locked user, 404 otherwise. The email is never logged |
| `GET /.well-known/jwks.json` | Public signing keys, internal only: the Gateway reads it, it is not routed |

The Gateway in front counts failed logins and step-ups for the lockouts, applies the rate limits and checks the revocation keys this service writes (`revoked:jti:*`, `revoked:sid:*`).

## Modules

```
domain/model                     User, RefreshToken, TokenPair, AccessTokenClaims, SignedToken, ports
domain/usecase                   LoginUseCase, RefreshSessionUseCase, LogoutUseCase, FinancialSessionUseCase, FindRecipientUseCase
infrastructure/entry-points
  api-rest                       AuthController, UserController, JwksController, ErrorResponse (spec §3.2)
infrastructure/driven-adapters
  jpa-repository                 Users, refresh tokens and devices; Flyway V1 schema, V2 demo users, V3 demo OTP secrets
  jwt                            RsaTokenSigner, SigningKeyFile (the RS256 key kept in a PEM file)
  argon2                         Argon2CredentialVerifier (passwords and PINs)
  totp                           TotpCodeVerifier (AES-256-GCM encrypted secrets)
  redis                          RedisRevocationStore
applications/app-service         Spring Boot application and configuration
```

## Signing key

The RS256 key is read from `JWT_SIGNING_KEY_FILE` (PKCS#8 PEM). If the file does not exist, Identity creates it on first start, so restarts keep every issued token valid and every instance signs with the same key. The `kid` is the key's RFC 7638 thumbprint. docker-compose keeps the file in the `identity-keys` volume; no private key is committed. Without the variable the key lives in memory and changes on every restart (tests and quick local runs only).

## Configuration

| Variable | Default | What |
|---|---|---|
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | `localhost:5432/identity_db` | Database |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Revocation store |
| `JWT_ISSUER` | `finova-identity` | `iss` of every token; the Gateway checks the same value |
| `JWT_SIGNING_KEY_FILE` | empty | PEM file of the signing key (see above) |
| `OTP_ENCRYPTION_KEY` | development key | AES-256 key of the OTP secrets. Demo default only: set your own outside the demo |

Demo users, PINs and TOTP secrets are listed in the root `README.md`; all use the password `Finova2026!`.

## Run and test

```bash
./gradlew build                      # unit tests + Testcontainers tests (PostgreSQL, Redis), coverage >= 90 %
docker compose --profile apps up -d --build identity-service   # from the repository root
```

Use-case tests are named after the spec scenarios they cover (`f12_…`, `f13_…`). `AuthApiIntegrationTest` runs login, refresh, reuse detection, logout, step-up and lookup against a real PostgreSQL and Redis. `docs/postman/finova-auth.postman_collection.json` exercises the same flows through the Gateway.
