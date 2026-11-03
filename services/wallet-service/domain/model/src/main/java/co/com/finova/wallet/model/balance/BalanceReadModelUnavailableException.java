package co.com.finova.wallet.model.balance;

/**
 * The balance read model could not be read or written: it failed or did not answer in time.
 * GET /wallets/me/balance then answers from wallet_db (spec §3.7, F2.6).
 */
public class BalanceReadModelUnavailableException extends RuntimeException {

    public BalanceReadModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
