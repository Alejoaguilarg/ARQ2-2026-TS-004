package co.com.finova.wallet.usecase.getbalance;

import co.com.finova.wallet.model.balance.BalanceReadModelUnavailableException;
import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.balance.gateways.BalanceReadModel;
import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.wallet.WalletNotFoundException;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * GET /wallets/me/balance (spec §3.7). Answers from the read model without querying wallet_db (F2.1).
 * On a miss, or when the read model fails or times out, reads wallet_db, writes the balance back with
 * the compare-and-set when the read model is reachable, and answers (F2.2, F2.6). The answer is
 * eventually consistent and never authorizes a debit.
 */
public class GetBalanceUseCase {

    private static final System.Logger LOG = System.getLogger(GetBalanceUseCase.class.getName());

    private final BalanceReadModel readModel;
    private final WalletRepository wallets;

    public GetBalanceUseCase(BalanceReadModel readModel, WalletRepository wallets) {
        this.readModel = readModel;
        this.wallets = wallets;
    }

    /** correlationId is only used in the log lines and may be null. */
    public BalanceSnapshot handle(UUID userId, String correlationId) {
        Contract.requireNonNull(userId, "userId");
        Optional<BalanceSnapshot> cached = read(userId, correlationId);
        if (cached.isPresent()) {
            return cached.get();
        }
        BalanceSnapshot fromWallet = wallets.findByUserId(userId)
                .map(BalanceSnapshot::fromWallet)
                .orElseThrow(() -> new WalletNotFoundException(userId));
        writeBack(fromWallet, correlationId);
        return fromWallet;
    }

    private Optional<BalanceSnapshot> read(UUID userId, String correlationId) {
        try {
            return readModel.find(userId);
        } catch (BalanceReadModelUnavailableException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "balance read model read failed, answering from wallet_db: "
                    + e.getMessage() + context(userId, correlationId));
            return Optional.empty();
        }
    }

    private void writeBack(BalanceSnapshot snapshot, String correlationId) {
        try {
            if (!readModel.storeIfNewer(snapshot)) {
                LOG.log(System.Logger.Level.INFO, () -> "balance read model already holds a newer version than "
                        + snapshot.version() + context(snapshot.userId(), correlationId));
            }
        } catch (BalanceReadModelUnavailableException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "balance read model write failed: " + e.getMessage()
                    + context(snapshot.userId(), correlationId));
        }
    }

    private static String context(UUID userId, String correlationId) {
        return " correlationId=" + correlationId + " userId=" + userId;
    }
}
