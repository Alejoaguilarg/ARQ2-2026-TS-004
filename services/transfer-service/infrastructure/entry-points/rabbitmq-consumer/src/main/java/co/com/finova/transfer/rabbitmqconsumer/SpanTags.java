package co.com.finova.transfer.rabbitmqconsumer;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Business tags on the span Spring AMQP opens for the delivery (spring.rabbit.listener, with observation-enabled).
 * Every tag is high cardinality: it goes to the span only, never to the spring.rabbit.listener metric, so it cannot
 * change the metric's tag keys. Observability only: no amounts, user data or tokens, and a failure here is logged
 * and never reaches the delivery.
 */
final class SpanTags {

    static final String TRANSFER_ID = "transfer.id";
    static final String MESSAGE_TYPE = "message.type";

    private static final Logger LOG = LoggerFactory.getLogger(SpanTags.class);

    private final ObservationRegistry registry;

    SpanTags(ObservationRegistry registry) {
        this.registry = registry;
    }

    /** Adds the tag to the current observation; does nothing without one or without a value. */
    void put(String key, Object value) {
        if (value == null) {
            return;
        }
        try {
            Observation current = registry.getCurrentObservation();
            if (current != null) {
                current.highCardinalityKeyValue(key, value.toString());
            }
        } catch (RuntimeException e) {
            LOG.debug("Span tag {} not added: {}", key, e.getMessage());
        }
    }
}
