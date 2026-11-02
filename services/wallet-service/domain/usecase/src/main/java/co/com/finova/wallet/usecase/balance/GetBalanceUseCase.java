package co.com.finova.wallet.usecase.balance;

import co.com.finova.wallet.model.balance.Balance;
import co.com.finova.wallet.model.balance.WalletNotFoundException;
import co.com.finova.wallet.model.balance.gateways.BalanceCache;
import co.com.finova.wallet.model.balance.gateways.WalletRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

import static java.lang.System.Logger.Level.WARNING;

@RequiredArgsConstructor
public class GetBalanceUseCase {

    private static final System.Logger LOG = System.getLogger(GetBalanceUseCase.class.getName());

    private final BalanceCache cache;
    private final WalletRepository wallets;

    public Balance execute(UUID userId) {
        Optional<Balance> cached;
        try {
            cached = cache.find(userId);
        } catch (RuntimeException e) {
            LOG.log(WARNING, "Balance cache read failed; serving from wallet_db", e);
            return fromDatabase(userId);
        }
        if (cached.isPresent()) {
            return cached.get();
        }
        Balance balance = fromDatabase(userId);
        try {
            cache.putIfNewer(balance);
        } catch (RuntimeException e) {
            LOG.log(WARNING, "Balance cache warm-up failed", e);
        }
        return balance;
    }

    private Balance fromDatabase(UUID userId) {
        return wallets.findBalanceByUserId(userId).orElseThrow(() -> new WalletNotFoundException(userId));
    }
}
