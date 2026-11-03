package co.com.finova.wallet.usecase.projectbalance;

import co.com.finova.wallet.model.balance.BalanceReadModelUnavailableException;
import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.money.Money;
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

/** Projection of BalanceChanged into the balance read model (spec §2.1, §4.5, ADR 004). */
class ProjectBalanceUseCaseTest {

    private final InMemoryPorts.Balances balances = new InMemoryPorts.Balances();
    private final InMemoryPorts.CapturedLogs logs = new InMemoryPorts.CapturedLogs();
    private final Logger useCaseLogger = Logger.getLogger(ProjectBalanceUseCase.class.getName());

    private final ProjectBalanceUseCase useCase = new ProjectBalanceUseCase(balances);

    private final UUID walletId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();

    @BeforeEach
    void captureLogs() {
        useCaseLogger.addHandler(logs);
    }

    @AfterEach
    void releaseLogs() {
        useCaseLogger.removeHandler(logs);
    }

    @Test
    void firstEventIsStoredWithCopAndOccurredAtAsAsOf() {
        useCase.handle(event("350000.00", 4));

        assertThat(balances.byUser.get(userId))
                .isEqualTo(new BalanceSnapshot(walletId, userId, Money.parse("350000.00"), Currency.COP, 4, NOW));
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("projected", correlationId.toString(), transferId.toString()));
    }

    @Test
    void newerEventReplacesTheStoredBalance() {
        useCase.handle(event("350000.00", 4));

        useCase.handle(event("200000.00", 5));

        assertThat(balances.byUser.get(userId).version()).isEqualTo(5);
        assertThat(balances.byUser.get(userId).available()).isEqualTo(Money.parse("200000.00"));
    }

    @Test
    void F2_3_olderEventIsDiscardedAndLogged() {
        useCase.handle(event("100.00", 9));

        useCase.handle(event("700.00", 7));

        assertThat(balances.byUser.get(userId).version()).isEqualTo(9);
        assertThat(balances.byUser.get(userId).available()).isEqualTo(Money.parse("100.00"));
        assertThat(logs.messages(Level.INFO)).anySatisfy(line -> assertThat(line).contains(
                "discarded", "version=7", correlationId.toString(), transferId.toString()));
    }

    @Test
    void readModelFailurePropagatesSoTheMessageIsRetried() {
        balances.failWrites = true;

        assertThatThrownBy(() -> useCase.handle(event("1.00", 1)))
                .isInstanceOf(BalanceReadModelUnavailableException.class);
    }

    @Test
    void rejectsMissingEvent() {
        assertThatThrownBy(() -> useCase.handle(null)).isInstanceOf(ContractViolationException.class);
    }

    private MessageEnvelope<BalanceChanged> event(String available, long version) {
        return MessageEnvelope.create(UUID.randomUUID(), correlationId, transferId, NOW,
                new BalanceChanged(walletId, userId, Money.parse(available), version));
    }
}
