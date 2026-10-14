# Spec Delta

## Purpose

Enforces strict idempotency at the API Gateway for all P2P transfer requests, preventing duplicate charges from user retries, network retries, or repeated button taps. Uses client-generated Idempotency-Key with request hash validation and TTL-based cleanup.

## ADDED Requirements

### Requirement: Gateway requires Idempotency-Key on transfer endpoints
The API Gateway SHALL reject any POST /transfers request missing the Idempotency-Key header with 400 Bad Request.

#### Scenario: Request without idempotency key rejected
- **WHEN** client sends POST /transfers without Idempotency-Key header
- **THEN** Gateway returns 400 Bad Request with error code IDEMPOTENCY_KEY_REQUIRED

### Requirement: Gateway registers idempotency key atomically with request hash
The API Gateway SHALL atomically register each Idempotency-Key in Redis using SETNX (set-if-absent) with a TTL of 24 hours, storing a SHA-256 hash of the request body alongside the key.

#### Scenario: First request with new key proceeds
- **WHEN** client sends POST /transfers with new Idempotency-Key and valid body
- **THEN** Gateway stores key + body hash in Redis, forwards request to Transfer Service, returns 202 Accepted

#### Scenario: Repeated key with identical body returns stored response
- **WHEN** client retries with same Idempotency-Key and identical request body while first request in progress
- **THEN** Gateway returns 409 Conflict with current status, or stored response if already completed

#### Scenario: Repeated key with different body rejected
- **WHEN** client reuses Idempotency-Key with different request body
- **THEN** Gateway returns 409 Conflict with error code IDEMPOTENCY_KEY_CONFLICT

### Requirement: Idempotency key scoped to user and endpoint
The API Gateway SHALL scope Idempotency-Key validation to the authenticated user and the specific endpoint (POST /transfers), preventing cross-user or cross-endpoint collisions.

#### Scenario: Same key by different user treated as separate
- **WHEN** User A and User B both use Idempotency-Key "abc-123" for POST /transfers
- **THEN** Gateway treats them as independent keys, both requests proceed

### Requirement: Transfer Service enforces unique constraint as defense in depth
The Transfer Service SHALL enforce a unique constraint on (idempotency_key, user_id) in the Transfer database, rejecting any duplicate that bypasses the Gateway.

#### Scenario: Duplicate key bypassing Gateway rejected by Transfer Service
- **WHEN** request with duplicate Idempotency-Key reaches Transfer Service directly
- **THEN** Transfer Service returns 409 Conflict due to unique constraint violation

### Requirement: Idempotency response replay for completed transfers
The API Gateway SHALL store the full response (status code, headers, body) for completed transfers and replay it on subsequent requests with the same Idempotency-Key.

#### Scenario: Retry after completion returns original response
- **WHEN** client retries with same Idempotency-Key after transfer completed
- **THEN** Gateway returns stored 202 Accepted response with original transfer ID and status endpoint