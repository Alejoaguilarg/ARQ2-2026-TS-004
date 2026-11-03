package co.com.finova.wallet.usecase.projectbalance;

import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.balance.gateways.BalanceReadModel;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.BalanceChanged;

/**
 * Projects BalanceChanged into the balance read model (spec §2.1, §4.5, ADR 004). Ordering is not
 * required: the compare-and-set on version discards an older or repeated event (F2.3). A read model
 * failure is rethrown so the message is retried from the queue (stage 6 wires the consumer).
 */
public class ProjectBalanceUseCase {

    private static final System.Logger LOG = System.getLogger(ProjectBalanceUseCase.class.getName());

    private final BalanceReadModel readModel;

    public ProjectBalanceUseCase(BalanceReadModel readModel) {
        this.readModel = readModel;
    }

    /** A missing event is rejected by {@link BalanceSnapshot#fromEvent} with a ContractViolationException. */
    public void handle(MessageEnvelope<BalanceChanged> event) {
        BalanceSnapshot snapshot = BalanceSnapshot.fromEvent(event);
        String outcome = readModel.storeIfNewer(snapshot)
                ? "BalanceChanged projected"
                : "BalanceChanged discarded: the read model holds a newer or equal version";
        LOG.log(System.Logger.Level.INFO, () -> outcome
                + " version=" + snapshot.version()
                + " userId=" + snapshot.userId()
                + " correlationId=" + event.correlationId()
                + " transferId=" + event.transferId());
    }
}
