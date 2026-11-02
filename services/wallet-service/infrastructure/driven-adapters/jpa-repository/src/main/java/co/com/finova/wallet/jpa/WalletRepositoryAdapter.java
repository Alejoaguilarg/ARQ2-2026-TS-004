package co.com.finova.wallet.jpa;

import co.com.finova.wallet.model.balance.Balance;
import co.com.finova.wallet.model.balance.gateways.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WalletRepositoryAdapter implements WalletRepository {

    private final WalletJpaRepository repository;

    @Override
    public Optional<Balance> findBalanceByUserId(UUID userId) {
        return repository.findByUserId(userId).map(WalletEntity::toBalance);
    }
}
