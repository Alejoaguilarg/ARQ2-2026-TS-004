package co.com.finova.wallet.api.balance;

import co.com.finova.wallet.model.balance.BalanceSnapshot;

/** Body of 200 GET /wallets/me/balance (spec §3.7): money as a two-decimal string (§1.5), asOf in RFC 3339 UTC. */
public record BalanceResponse(String walletId, String available, String currency, long version, String asOf) {

    public static BalanceResponse from(BalanceSnapshot snapshot) {
        return new BalanceResponse(snapshot.walletId().toString(), snapshot.available().toString(),
                snapshot.currency().name(), snapshot.version(), snapshot.asOf().toString());
    }
}
