package co.com.finova.wallet.model.balance.gateways;

import co.com.finova.wallet.model.balance.Balance;

import java.util.Optional;
import java.util.UUID;

public interface BalanceCache {

    Optional<Balance> find(UUID userId);

    // Writes only if the key is missing or holds an older version; returns false when rejected.
    boolean putIfNewer(Balance balance);
}
