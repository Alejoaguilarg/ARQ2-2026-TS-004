package co.com.finova.transfer.sagatelemetry;

import co.com.finova.transfer.model.transfer.SagaTrigger;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.SagaTelemetry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * One observation "transfer.saga.transition" per saga transition (spec §4.6), wrapping the real work: a span in the
 * trace and a timer of its own. Observability only; the spec does not define these keys.
 * <ul>
 *   <li>Low cardinality (span and timer tags): transfer.status.from, transfer.status.to, saga.trigger,
 *       transfer.failure_reason, saga.credit.attempt and saga.ignored. All of them are set when the observation
 *       starts ("none" until the work reports), so every series of the timer has the same tag keys.</li>
 *   <li>High cardinality (span only): transfer.id and correlation.id. Never low cardinality: one series per
 *       transfer would blow up Prometheus.</li>
 * </ul>
 * No amounts and no user data. Any exception of the work is recorded on the span and rethrown unchanged.
 */
@Component
public class ObservationSagaTelemetry implements SagaTelemetry {

    static final String NAME = "transfer.saga.transition";
    static final String STATUS_FROM = "transfer.status.from";
    static final String STATUS_TO = "transfer.status.to";
    static final String TRIGGER = "saga.trigger";
    static final String FAILURE_REASON = "transfer.failure_reason";
    static final String CREDIT_ATTEMPT = "saga.credit.attempt";
    static final String IGNORED = "saga.ignored";
    static final String TRANSFER_ID = "transfer.id";
    static final String CORRELATION_ID = "correlation.id";

    /** The value of a key the work has not reported (or that does not apply). */
    private static final String NO_VALUE = "none";
    /** transfer.status.from of a transfer that did not exist before the transition. */
    private static final String NEW = "NONE";

    private final ObservationRegistry registry;

    public ObservationSagaTelemetry(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T> T transition(SagaTrigger trigger, Function<Transition, T> work) {
        Observation observation = Observation.createNotStarted(NAME, registry)
                .contextualName("saga " + trigger.label())
                .lowCardinalityKeyValue(TRIGGER, trigger.label())
                .lowCardinalityKeyValue(STATUS_FROM, NO_VALUE)
                .lowCardinalityKeyValue(STATUS_TO, NO_VALUE)
                .lowCardinalityKeyValue(FAILURE_REASON, NO_VALUE)
                .lowCardinalityKeyValue(CREDIT_ATTEMPT, NO_VALUE)
                .lowCardinalityKeyValue(IGNORED, "false");
        return observation.observe(() -> work.apply(new Report(observation)));
    }

    /** What the work reports, written on its observation. */
    private static final class Report implements Transition {

        private final Observation observation;

        private Report(Observation observation) {
            this.observation = observation;
        }

        @Override
        public void transitioned(Transfer from, Transfer to) {
            String fromStatus = from == null ? NEW : from.status().name();
            describe(fromStatus, to, false);
            // A CreditWallet is sent whenever the transfer is (still) DEBITED after the transition.
            if (to.status() == TransferStatus.DEBITED) {
                observation.lowCardinalityKeyValue(CREDIT_ATTEMPT, String.valueOf(to.creditAttempts()));
            }
        }

        @Override
        public void ignored(Transfer current) {
            describe(current.status().name(), current, true);
        }

        private void describe(String fromStatus, Transfer to, boolean ignored) {
            observation.lowCardinalityKeyValue(STATUS_FROM, fromStatus)
                    .lowCardinalityKeyValue(STATUS_TO, to.status().name())
                    .lowCardinalityKeyValue(FAILURE_REASON,
                            to.failureReason() == null ? NO_VALUE : to.failureReason().name())
                    .lowCardinalityKeyValue(IGNORED, String.valueOf(ignored))
                    .highCardinalityKeyValue(TRANSFER_ID, to.transferId().toString())
                    .highCardinalityKeyValue(CORRELATION_ID, to.correlationId().toString())
                    .contextualName("saga " + fromStatus + " -> " + to.status().name());
        }
    }
}
