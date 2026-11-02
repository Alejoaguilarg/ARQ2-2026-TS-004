package co.com.finova.wallet.usecase.balance;

import co.com.finova.wallet.model.balance.Balance;
import co.com.finova.wallet.model.balance.gateways.BalanceCache;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdateBalanceProjectionUseCase {

    private final BalanceCache cache;

    // Applies a BalanceChanged event; false means a newer version is already cached and the event is dropped.
    public boolean execute(Balance balance) {
        return cache.putIfNewer(balance);
    }
}
