package co.com.finova.wallet.model.balance;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(UUID userId) {
        super("No wallet for user " + userId);
    }
}
