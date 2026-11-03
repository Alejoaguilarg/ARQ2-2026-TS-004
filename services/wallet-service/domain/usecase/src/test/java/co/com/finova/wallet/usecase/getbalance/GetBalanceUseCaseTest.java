package co.com.finova.wallet.usecase.getbalance;

import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.WalletNotFoundException;
import co.com.finova.wallet.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static co.com.finova.wallet.usecase.support.InMemoryPorts.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Read path of GET /wallets/me/balance (spec §3.7, F2.1, F2.2, F2.4, F2.6) with in-memory ports. */
class GetBalanceUseCaseTest {

    private final InMemoryPorts.Balances balances = new InMemoryPorts.Balances();
    private final InMemoryPorts.Wallets wallets = new InMemoryPorts.Wallets();
    private final InMemoryPorts.CapturedLogs logs = new InMemoryPorts.CapturedLogs();
    private final Logger useCaseLogger = Logger.getLogger(GetBalanceUseCase.class.getName());

    private final GetBalanceUseCase useCase = new GetBalanceUseCase(balances, wallets);

    private final UUID userId = UUID.randomUUID();
    private final String correlationId = UUID.randomUUID().toString();

    @BeforeEach
    void captureLogs() {
        useCaseLogger.addHandler(logs);
    }

    @AfterEach
    void releaseLogs() {
        useCaseLogger.removeHandler(logs);
    }

    @Test
    void F2_1_hitAnswersFromReadModelWithoutReadingTheWallet() {
        BalanceSnapshot cached = snapshot("850000.00", 42);
        balances.put(cached);
        wallets.add(userId, "1.00", 1);

        assertThat(useCase.handle(userId, correlationId)).isEqualTo(cached);

        assertThat(wallets.finds).isZero();
        assertThat(balances.writeAttempts).isZero();
    }

    @Test
    void F2_2_missReadsTheWalletStoresItWithItsVersionAndAnswers() {
        Wallet wallet = wallets.add(userId, "500000.00", 3);

        BalanceSnapshot answered = useCase.handle(userId, correlationId);

        assertThat(answered).isEqualTo(BalanceSnapshot.fromWallet(wallet));
        assertThat(answered.asOf()).isEqualTo(wallet.updatedAt());
        assertThat(balances.byUser.get(userId)).isEqualTo(answered);
        assertThat(wallets.finds).isEqualTo(1);
    }

    @Test
    void missWithoutWalletThrowsWalletNotFoundAndStoresNothing() {
        assertThatThrownBy(() -> useCase.handle(userId, correlationId))
                .isInstanceOf(WalletNotFoundException.class)
                .extracting(e -> ((WalletNotFoundException) e).userId()).isEqualTo(userId);

        assertThat(balances.writeAttempts).isZero();
    }

    @Test
    void F2_6_readFailureFallsBackToTheWalletAndLogsAWarning() {
        Wallet wallet = wallets.add(userId, "500000.00", 3);
        balances.put(snapshot("1.00", 99));
        balances.failReads = true;

        assertThat(useCase.handle(userId, correlationId)).isEqualTo(BalanceSnapshot.fromWallet(wallet));

        assertThat(wallets.finds).isEqualTo(1);
        assertThat(logs.messages(Level.WARNING)).anySatisfy(line ->
                assertThat(line).contains("read", correlationId, userId.toString()));
    }

    @Test
    void F2_6_writeFailureAfterTheFallbackStillAnswersAndLogsAWarning() {
        Wallet wallet = wallets.add(userId, "500000.00", 3);
        balances.failWrites = true;

        assertThat(useCase.handle(userId, correlationId)).isEqualTo(BalanceSnapshot.fromWallet(wallet));

        assertThat(balances.writeAttempts).isEqualTo(1);
        assertThat(logs.messages(Level.WARNING)).anySatisfy(line ->
                assertThat(line).contains("write", correlationId, userId.toString()));
    }

    @Test
    void F2_4_warmUpDoesNotOverwriteANewerProjectionWrittenMeanwhile() {
        Wallet wallet = wallets.add(userId, "500000.00", 8);
        BalanceSnapshot projected = snapshot("350000.00", 9);
        balances.beforeWrite = () -> balances.put(projected);

        BalanceSnapshot answered = useCase.handle(userId, correlationId);

        assertThat(answered).isEqualTo(BalanceSnapshot.fromWallet(wallet));
        assertThat(balances.byUser.get(userId)).isEqualTo(projected);
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("newer", correlationId, userId.toString()));
    }

    @Test
    void acceptsMissingCorrelationId() {
        wallets.add(userId, "10.00", 0);

        assertThat(useCase.handle(userId, null).available()).isEqualTo(Money.parse("10.00"));
    }

    @Test
    void rejectsMissingUser() {
        assertThatThrownBy(() -> useCase.handle(null, correlationId)).isInstanceOf(ContractViolationException.class);
    }

    private BalanceSnapshot snapshot(String available, long version) {
        return new BalanceSnapshot(UUID.randomUUID(), userId, Money.parse(available), Currency.COP, version,
                NOW.minusSeconds(5));
    }
}
