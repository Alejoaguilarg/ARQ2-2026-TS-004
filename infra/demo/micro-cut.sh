#!/usr/bin/env bash
# Demo of failure mode 1: a network micro-cut between the debit and the credit (spec F4.3).
#
# While the cut lasts, the CreditWallet commands of Transfer never reach Wallet: the broker routes the
# "credit" key of wallet.commands to a hold queue instead of wallet.commands.q. Transfer still gets its publisher
# confirm (nothing fails on its side), exactly as when the network drops the message. "debit" and "reverse" keep
# working. The saga must time out three credit attempts (10 s each), compensate, and give Ana her money back.
#
# Usage: infra/demo/micro-cut.sh            (with the whole system up: docker compose --profile apps up -d)
source "$(dirname "$0")/lib.sh"

HOLD_QUEUE="demo.micro-cut.q"
AMOUNT="50000.00"

rabbit() { curl -fsS -u "$RABBIT_AUTH" -H 'Content-Type: application/json' "$@" >/dev/null; }

cut_credit() {
  # The hold queue takes the credits first, so a CreditWallet is never unroutable (Transfer would retry it).
  rabbit -X PUT "$RABBIT_API/queues/%2F/$HOLD_QUEUE" -d '{"durable":false,"auto_delete":false}'
  rabbit -X POST "$RABBIT_API/bindings/%2F/e/wallet.commands/q/$HOLD_QUEUE" -d '{"routing_key":"credit"}'
  rabbit -X DELETE "$RABBIT_API/bindings/%2F/e/wallet.commands/q/wallet.commands.q/credit"
}

restore_credit() {
  # Same order backwards: wallet.commands.q is bound again before the hold queue goes away.
  rabbit -X POST "$RABBIT_API/bindings/%2F/e/wallet.commands/q/wallet.commands.q" -d '{"routing_key":"credit"}' || true
  rabbit -X DELETE "$RABBIT_API/queues/%2F/$HOLD_QUEUE" || true
}

held_credits() {
  curl -fsS -u "$RABBIT_AUTH" "$RABBIT_API/queues/%2F/$HOLD_QUEUE" | json_field messages
}

ANA="$(login ana@example.com)"
BEFORE="$(balance "$ANA")"
echo "Ana's balance before: $BEFORE COP"

echo "Cutting the network between Transfer and Wallet for credits..."
trap restore_credit EXIT
cut_credit

FS="$(financial_session "$ANA" 4821)"
TRANSFER_ID="$(curl -fsS "$GATEWAY/transfers" -H "Authorization: Bearer $ANA" -H 'Content-Type: application/json' \
  -H "X-Financial-Session: $FS" -H "Idempotency-Key: $(uuid)" \
  -d "{\"recipientUserId\":\"$BETO_ID\",\"amount\":\"$AMOUNT\",\"currency\":\"COP\"}" | json_field transferId)"
echo "Ana sends $AMOUNT COP to Beto: transfer $TRANSFER_ID"

LAST=""
STATUS=""
START=$SECONDS
while (( SECONDS - START < 120 )); do
  STATUS="$(transfer_status "$ANA" "$TRANSFER_ID")"
  if [[ "$STATUS" != "$LAST" ]]; then
    printf '  +%3ss  %s\n' "$((SECONDS - START))" "$STATUS"
    LAST="$STATUS"
  fi
  [[ "$STATUS" == "REVERSED" || "$STATUS" == "COMPLETED" || "$STATUS" == "REJECTED" ]] && break
  sleep 1
done

echo "CreditWallet attempts lost in the cut: $(held_credits)"
restore_credit
trap - EXIT
echo "Network restored."

sleep 1
echo "Ana's balance after:  $(balance "$ANA") COP (before: $BEFORE COP)"
echo "Audit trail of the transfer:"
audit_trail "$TRANSFER_ID"

[[ "$STATUS" == "REVERSED" ]] && echo "OK: the saga compensated on its own; no money was lost." \
  || { echo "UNEXPECTED: the transfer ended $STATUS"; exit 1; }
