package co.com.finova.wallet.model.balance.gateways;

import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.balance.BalanceReadModelUnavailableException;

import java.util.Optional;
import java.util.UUID;

/**
 * Balance read model (spec §2.1, ADR 004). Both methods throw
 * {@link BalanceReadModelUnavailableException} when the store fails or times out.
 */
public interface BalanceReadModel {

    Optional<BalanceSnapshot> find(UUID userId);

    /**
     * Atomic compare-and-set: writes only if there is no entry for the user or the snapshot version is
     * greater than the stored one, so a late write never overwrites a newer balance (F2.3, F2.4).
     *
     * @return true when the snapshot was written
     */
    boolean storeIfNewer(BalanceSnapshot snapshot);
}
