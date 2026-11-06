package co.com.finova.transfer.model.transfer.gateways;

import co.com.finova.transfer.model.transfer.SagaTrigger;
import co.com.finova.transfer.model.transfer.Transfer;

import java.util.function.Function;

/**
 * Describes each saga transition (spec §4.6) in the traces. Observability only: it never decides anything, and the
 * work runs exactly the same with or without it. The work reports what it did through {@link Transition}; any
 * exception it throws reaches the caller unchanged.
 */
public interface SagaTelemetry {

    /** Telemetry that records nothing: the work just runs. */
    SagaTelemetry NOOP = new SagaTelemetry() {
        @Override
        public <T> T transition(SagaTrigger trigger, Function<Transition, T> work) {
            return work.apply(Transition.NONE);
        }
    };

    /** Runs the work of one transition inside its trace span and returns what the work returns. */
    <T> T transition(SagaTrigger trigger, Function<Transition, T> work);

    /** What the work reports; reporting nothing leaves the span without statuses. */
    interface Transition {

        /** A report that goes nowhere. */
        Transition NONE = new Transition() {
            @Override
            public void transitioned(Transfer from, Transfer to) {
                // nothing recorded
            }

            @Override
            public void ignored(Transfer current) {
                // nothing recorded
            }
        };

        /** The transfer moved from one status to another; from is null when it was just created (NONE). */
        void transitioned(Transfer from, Transfer to);

        /** Rule 9: the event is not valid for the current status and is ignored; the status does not change. */
        void ignored(Transfer current);
    }
}
