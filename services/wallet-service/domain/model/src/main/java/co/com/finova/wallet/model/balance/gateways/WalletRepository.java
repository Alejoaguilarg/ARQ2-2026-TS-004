package co.com.finova.wallet.model.balance.gateways;

import co.com.finova.wallet.model.balance.Balance;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    Optional<Balance> findBalanceByUserId(UUID userId);
}
