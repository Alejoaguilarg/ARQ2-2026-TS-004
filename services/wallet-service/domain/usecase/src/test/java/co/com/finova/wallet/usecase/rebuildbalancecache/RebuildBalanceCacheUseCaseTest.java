package co.com.finova.wallet.usecase.rebuildbalancecache;

import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.usecase.support.InMemoryPorts;
import co.com.finova.wallet.usecase.support.InMemoryPorts.CapturedLogs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §4.5 and ADR 004: the rebuild job recomputes the balance cache from wallet_db (decision S6). */
class RebuildBalanceCacheUseCaseTest {

    private final InMemoryPorts.Balances balances = new InMemoryPorts.Balances();
    private final InMemoryPorts.Wallets wallets = new InMemoryPorts.Wallets();
    private final RebuildBalanceCacheUseCase useCase = new RebuildBalanceCacheUseCase(wallets, balances);

    private final Logger logger = Logger.getLogger(RebuildBalanceCacheUseCase.class.getName());
    private final CapturedLogs logs = new CapturedLogs();

    @BeforeEach
    void captureLogs() {
        logger.addHandler(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.removeHandler(logs);
    }

    @Test
    void writesEveryWalletAcrossPages() {
        Wallet a = wallets.add(UUID.randomUUID(), "500000.00", 3);
        Wallet b = wallets.add(UUID.randomUUID(), "100000.00", 0);
        Wallet c = wallets.add(UUID.randomUUID(), "50000.00", 7);

        RebuildBalanceCacheUseCase.Result result = useCase.rebuild(2);

        assertThat(result).isEqualTo(new RebuildBalanceCacheUseCase.Result(3, 0, true));
        assertThat(balances.byUser).containsValues(BalanceSnapshot.fromWallet(a), BalanceSnapshot.fromWallet(b),
                BalanceSnapshot.fromWallet(c));
    }

    @Test
    void F2_4_rebuildNeverOverwritesANewerVersion() {
        Wallet wallet = wallets.add(UUID.randomUUID(), "500000.00", 8);
        BalanceSnapshot newer = new BalanceSnapshot(wallet.walletId(), wallet.userId(), Money.parse("420000.00"),
                Currency.COP, 9, Instant.parse("2026-11-02T15:00:01Z"));
        balances.put(newer);

        RebuildBalanceCacheUseCase.Result result = useCase.rebuild(10);

        assertThat(result).isEqualTo(new RebuildBalanceCacheUseCase.Result(0, 1, true));
        assertThat(balances.byUser.get(wallet.userId())).isEqualTo(newer);
    }

    @Test
    void repairsAStaleEntry() {
        Wallet wallet = wallets.add(UUID.randomUUID(), "350000.00", 5);
        balances.put(new BalanceSnapshot(wallet.walletId(), wallet.userId(), Money.parse("500000.00"), Currency.COP,
                4, Instant.parse("2026-11-02T14:00:00Z")));

        useCase.rebuild(10);

        assertThat(balances.byUser.get(wallet.userId())).isEqualTo(BalanceSnapshot.fromWallet(wallet));
    }

    @Test
    void emptyWalletTableIsACompleteRun() {
        assertThat(useCase.rebuild(10)).isEqualTo(new RebuildBalanceCacheUseCase.Result(0, 0, true));
    }

    @Test
    void readModelOutageStopsTheRunAndIsLogged() {
        wallets.add(UUID.randomUUID(), "1.00", 0);
        wallets.add(UUID.randomUUID(), "2.00", 0);
        balances.failWrites = true;

        RebuildBalanceCacheUseCase.Result result = useCase.rebuild(10);

        assertThat(result).isEqualTo(new RebuildBalanceCacheUseCase.Result(0, 0, false));
        assertThat(balances.writeAttempts).as("stops at the first failure").isEqualTo(1);
        assertThat(logs.messages(Level.WARNING)).singleElement().asString()
                .contains("balance cache rebuild stopped").contains("userId=");
    }

    @Test
    void pageSizeMustBePositive() {
        assertThatThrownBy(() -> useCase.rebuild(0)).isInstanceOf(ContractViolationException.class);
    }
}
