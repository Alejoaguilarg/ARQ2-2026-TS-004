package co.com.finova.wallet.jpa;

import co.com.finova.wallet.model.balance.Balance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
@Check(constraints = "balance >= 0")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class WalletEntity {

    @Id
    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    Balance toBalance() {
        return new Balance(walletId, userId, balance, currency, version, updatedAt);
    }
}
