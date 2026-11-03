#!/usr/bin/env bash
# Shared helpers for the demo scripts. Only bash, curl and sed: no jq or python needed.
set -euo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
RABBIT_API="${RABBITMQ_API:-http://localhost:15672/api}"
RABBIT_AUTH="${RABBITMQ_USER:-finova}:${RABBITMQ_PASSWORD:-finova}"

ANA_ID="7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c"
BETO_ID="2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f"

# json_field FIELD: the first string or number value of FIELD in the JSON on stdin.
json_field() {
  sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -n 1
}

uuid() {
  cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z'
}

# login EMAIL: prints an access token (demo password from README).
login() {
  curl -fsS "$GATEWAY/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"Finova2026!\",\"deviceId\":\"demo-$1\"}" | json_field accessToken
}

# financial_session TOKEN PIN: prints a single-use financial session token (spec §3.6).
financial_session() {
  curl -fsS "$GATEWAY/auth/financial-session" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"method\":\"PIN\",\"credential\":\"$2\",\"scope\":\"transfer:create\"}" | json_field financialSessionToken
}

# balance TOKEN: prints the available balance.
balance() {
  curl -fsS "$GATEWAY/wallets/me/balance" -H "Authorization: Bearer $1" | json_field available
}

# transfer_status TOKEN TRANSFER_ID
transfer_status() {
  curl -fsS "$GATEWAY/transfers/$2" -H "Authorization: Bearer $1" | json_field status
}

# audit QUERY: one line per audit record matching QUERY (e.g. transferId=...), as the auditor sees it (§3.10).
audit() {
  local auditor
  auditor="$(login auditor@example.com)"
  { curl -fsS "$GATEWAY/audit/records?$1&size=200" -H "Authorization: Bearer $auditor"; echo; } \
    | sed 's/},{/}\n{/g' \
    | sed -n 's/.*"seq":\([0-9]*\).*"source":"\([A-Z]*\)".*"stage":"\([A-Z]*\)".*"outcome":"\([A-Z]*\)","reason":"\{0,1\}\([A-Z_a-z]*\).*"idempotencyKey":"\{0,1\}\([^",]*\).*/  #\1 \2 \3 \4 \5|\6/p' | sed 's/ null|/ |/'
}

# audit_trail TRANSFER_ID: the audit records of one transfer.
audit_trail() {
  audit "transferId=$1" | sed 's/|.*//'
}

# audit_for_key USER_ID FROM KEY: the records of one Idempotency-Key, including the Gateway's duplicates (which
# have no transferId).
audit_for_key() {
  audit "userId=$1&from=$2" | grep "|$3\$" | sed 's/|.*//'
}
