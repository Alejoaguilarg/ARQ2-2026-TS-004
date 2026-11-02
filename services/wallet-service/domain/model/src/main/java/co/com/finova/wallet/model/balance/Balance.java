package co.com.finova.wallet.model.balance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Balance(UUID walletId, UUID userId, BigDecimal available, String currency, long version, Instant asOf) {
}
