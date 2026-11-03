package co.com.finova.wallet.usecase.rebuildbalancecache;

import co.com.finova.wallet.model.balance.BalanceReadModelUnavailableException;
import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.balance.gateways.BalanceReadModel;
import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;

import java.util.List;
import java.util.UUID;

/**
 * Balance cache rebuild job (spec §4.5, ADR 004; decision S6): recomputes balance:{userId} from wallet_db, the
 * system of record, page by page. Every write goes through the compare-and-set, so a run never overwrites a
 * newer version written meanwhile by the projection (F2.4). It repairs entries left stale by a BalanceChanged
 * that ended in the .dlq. If the read model fails, the run stops and the next one starts over.
 */
public class RebuildBalanceCacheUseCase {

    private static final System.Logger LOG = System.getLogger(RebuildBalanceCacheUseCase.class.getName());

    private final WalletRepository wallets;
    private final BalanceReadModel readModel;

    public RebuildBalanceCacheUseCase(WalletRepository wallets, BalanceReadModel readModel) {
        this.wallets = wallets;
        this.readModel = readModel;
    }

    /**
     * @param written  entries written because they were missing or older
     * @param skipped  entries left as they were because the read model already held that version or a newer one
     * @param complete false when the read model failed and the run stopped early
     */
    public record Result(int written, int skipped, boolean complete) {
    }

    public Result rebuild(int pageSize) {
        Contract.require(pageSize > 0, "pageSize must be > 0: " + pageSize);
        int written = 0;
        int skipped = 0;
        UUID after = null;
        List<Wallet> page = wallets.findPageAfter(null, pageSize);
        while (!page.isEmpty()) {
            for (Wallet wallet : page) {
                try {
                    if (readModel.storeIfNewer(BalanceSnapshot.fromWallet(wallet))) {
                        written++;
                    } else {
                        skipped++;
                    }
                } catch (BalanceReadModelUnavailableException e) {
                    LOG.log(System.Logger.Level.WARNING, () -> "balance cache rebuild stopped, the next run retries: "
                            + e.getMessage() + " userId=" + wallet.userId());
                    return new Result(written, skipped, false);
                }
                after = wallet.walletId();
            }
            page = page.size() < pageSize ? List.of() : wallets.findPageAfter(after, pageSize);
        }
        return new Result(written, skipped, true);
    }
}
