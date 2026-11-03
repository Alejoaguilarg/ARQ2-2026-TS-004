package co.com.finova.wallet.model;

import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;

import java.time.Instant;
import java.util.UUID;

public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    private Fixtures() {
    }

    public static Money money(String text) {
        return Money.parse(text);
    }

    public static Wallet wallet(String balance, long version) {
        return new Wallet(UUID.randomUUID(), UUID.randomUUID(), Money.parse(balance), Currency.COP, version, NOW);
    }
}
