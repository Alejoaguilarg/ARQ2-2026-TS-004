package co.com.finova.transfer.jpa.transfer;

import co.com.finova.transfer.jpa.support.PostgresTestBase;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.transfer.DuplicateIdempotencyKeyException;
import co.com.finova.transfer.model.transfer.FailureReason;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.model.transfer.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §2 Transfer in transfer_db: round trip, UNIQUE(sender_user_id, idempotency_key), sweeper scan, row lock. */
class JdbcTransferRepositoryTest extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00.123456Z");
    private static final String HASH = "b".repeat(64);

    private final JdbcTransferRepository repository = new JdbcTransferRepository(JdbcClient.create(jdbc));
    private final UUID sender = UUID.randomUUID();

    private Transfer newTransfer(UUID sender, UUID key, Instant at) {
        TransferRequest request = new TransferRequest(sender, UUID.randomUUID(), Money.parse("150000.00"),
                Currency.COP, "Dinner", key, HASH);
        return Transfer.create(request, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), at);
    }

    @Test
    void aTransferIsStoredAndReadBackUnchanged() {
        Transfer transfer = newTransfer(sender, UUID.randomUUID(), NOW);

        repository.insert(transfer);

        assertThat(repository.findById(transfer.transferId())).contains(transfer);
        assertThat(repository.findByIdempotencyKey(sender, transfer.idempotencyKey())).contains(transfer);
        assertThat(repository.findByIdempotencyKey(UUID.randomUUID(), transfer.idempotencyKey())).isEmpty();
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void anUpdateStoresTheNewStateOfTheSaga() {
        Transfer transfer = newTransfer(sender, UUID.randomUUID(), NOW);
        repository.insert(transfer);
        Transfer compensating = transfer.debited(UUID.randomUUID(), NOW.plusSeconds(1))
                .compensating(FailureReason.CREDIT_TIMEOUT, UUID.randomUUID(), NOW.plusSeconds(31));
        Transfer completed = compensating.completed(NOW.plusSeconds(40));

        repository.update(compensating);
        assertThat(repository.lockById(transfer.transferId())).contains(compensating);
        repository.update(completed);

        assertThat(repository.findById(transfer.transferId())).contains(completed);
        assertThatThrownBy(() -> repository.update(newTransfer(sender, UUID.randomUUID(), NOW)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSameSenderCannotReuseAnIdempotencyKey_s3_1_1() {
        UUID key = UUID.randomUUID();
        repository.insert(newTransfer(sender, key, NOW));

        assertThatThrownBy(() -> repository.insert(newTransfer(sender, key, NOW)))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
        repository.insert(newTransfer(UUID.randomUUID(), key, NOW));
    }

    @Test
    void theSchemaRefusesInvalidRows() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transfers (transfer_id, sender_user_id, recipient_user_id, amount, currency,
                    idempotency_key, request_hash, status, correlation_id, last_command_id, last_command_at,
                    created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 10.00, 'COP', gen_random_uuid(), ?, 'PENDING', gen_random_uuid(),
                    gen_random_uuid(), now(), now(), now())""", sender, sender, HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSweeperFindsOnlyRunningTransfersThatWaitedTooLong_rule8() {
        Transfer stalePending = newTransfer(sender, UUID.randomUUID(), NOW.minusSeconds(31));
        Transfer freshPending = newTransfer(sender, UUID.randomUUID(), NOW.minusSeconds(5));
        Transfer staleDebited = newTransfer(sender, UUID.randomUUID(), NOW.minusSeconds(60))
                .debited(UUID.randomUUID(), NOW.minusSeconds(11));
        Transfer freshDebited = newTransfer(sender, UUID.randomUUID(), NOW.minusSeconds(60))
                .debited(UUID.randomUUID(), NOW.minusSeconds(5));
        Transfer staleCompensating = newTransfer(sender, UUID.randomUUID(), NOW.minusSeconds(90))
                .debited(UUID.randomUUID(), NOW.minusSeconds(80))
                .compensating(FailureReason.CREDIT_TIMEOUT, UUID.randomUUID(), NOW.minusSeconds(40));
        Transfer completed = newTransfer(sender, UUID.randomUUID(), NOW.minusSeconds(90))
                .debited(UUID.randomUUID(), NOW.minusSeconds(80)).completed(NOW.minusSeconds(70));
        for (Transfer t : List.of(stalePending, freshPending, staleDebited, freshDebited, staleCompensating, completed)) {
            repository.insert(t);
        }

        List<UUID> due = repository.findDue(NOW.minusSeconds(30), NOW.minusSeconds(10), NOW.minusSeconds(30), 10);

        // Oldest command first: COMPENSATING sent 40 s ago, PENDING 31 s ago, DEBITED 11 s ago.
        assertThat(due).containsExactly(staleCompensating.transferId(), stalePending.transferId(),
                staleDebited.transferId());
        assertThat(repository.findDue(NOW.minusSeconds(30), NOW.minusSeconds(10), NOW.minusSeconds(30), 1))
                .containsExactly(staleCompensating.transferId());
        assertThat(repository.findById(completed.transferId()).orElseThrow().status())
                .isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void aLockedTransferMakesASecondLockerWait() throws Exception {
        Transfer transfer = newTransfer(sender, UUID.randomUUID(), NOW);
        repository.insert(transfer);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            repository.lockById(transfer.transferId());
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Transfer> second = CompletableFuture.supplyAsync(() ->
                tx.execute(status -> repository.lockById(transfer.transferId()).orElseThrow()));

        Thread.sleep(300);
        assertThat(second).isNotDone();
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(transfer);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
