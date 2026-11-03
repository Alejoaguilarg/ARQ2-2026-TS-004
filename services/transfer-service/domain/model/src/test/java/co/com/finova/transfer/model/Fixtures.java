package co.com.finova.transfer.model;

import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.money.Money;

import java.time.Instant;
import java.util.UUID;

public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    private Fixtures() {
    }

    public static Money money(String text) {
        return Money.parse(text);
    }

}
