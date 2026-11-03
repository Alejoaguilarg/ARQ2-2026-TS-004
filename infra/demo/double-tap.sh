#!/usr/bin/env bash
# Demo of failure mode 2: the user taps "Transfer" 10 times (spec F3.3). The app sends the same Idempotency-Key
# on every tap; exactly one transfer and one debit must exist.
#
# Usage: infra/demo/double-tap.sh            (with the whole system up: docker compose --profile apps up -d)
source "$(dirname "$0")/lib.sh"

TAPS=10
AMOUNT="10000.00"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

ANA="$(login ana@example.com)"
BEFORE="$(balance "$ANA")"
echo "Ana's balance before: $BEFORE COP"

FS="$(financial_session "$ANA" 4821)"
KEY="$(uuid)"
FROM="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Ana taps 'Transfer' $TAPS times at once (Idempotency-Key $KEY)..."
for i in $(seq "$TAPS"); do
  curl -sS -o "$OUT/$i.json" -w '%{http_code}\n' "$GATEWAY/transfers" -H "Authorization: Bearer $ANA" \
    -H 'Content-Type: application/json' -H "X-Financial-Session: $FS" -H "Idempotency-Key: $KEY" \
    -d "{\"recipientUserId\":\"$BETO_ID\",\"amount\":\"$AMOUNT\",\"currency\":\"COP\"}" > "$OUT/$i.code" &
done
wait
echo "Answers:"
cat "$OUT"/*.code | sort | uniq -c | sed 's/^ */  /'

TRANSFER_ID="$(cat "$OUT"/*.json | json_field transferId)"
sleep 3
echo "Transfer $TRANSFER_ID: $(transfer_status "$ANA" "$TRANSFER_ID")"

echo "One more tap after it finished (replay):"
curl -sS -D - -o /dev/null "$GATEWAY/transfers" -H "Authorization: Bearer $ANA" -H 'Content-Type: application/json' \
  -H "X-Financial-Session: $FS" -H "Idempotency-Key: $KEY" \
  -d "{\"recipientUserId\":\"$BETO_ID\",\"amount\":\"$AMOUNT\",\"currency\":\"COP\"}" \
  | grep -iE '^HTTP|^Idempotent-Replayed' | sed 's/^/  /'

echo "Ana's balance after:  $(balance "$ANA") COP (before: $BEFORE COP, one debit of $AMOUNT)"
echo "Audit trail of the Idempotency-Key (Gateway duplicates included):"
sleep 2
audit_for_key "$ANA_ID" "$FROM" "$KEY"
