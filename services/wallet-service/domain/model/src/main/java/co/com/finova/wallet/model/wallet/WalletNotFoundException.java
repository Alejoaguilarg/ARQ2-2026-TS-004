package co.com.finova.wallet.model.wallet;

import java.util.UUID;

/** The user has no wallet: GET /wallets/me/balance answers 404 WALLET_NOT_FOUND (spec §3.2, §3.7). */
public class WalletNotFoundException extends RuntimeException {

    private final UUID userId;

    public WalletNotFoundException(UUID userId) {
        super("user " + userId + " has no wallet");
        this.userId = userId;
    }

    public UUID userId() {
        return userId;
    }
}
