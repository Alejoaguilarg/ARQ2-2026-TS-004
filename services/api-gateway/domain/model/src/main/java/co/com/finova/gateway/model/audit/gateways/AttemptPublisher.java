package co.com.finova.gateway.model.audit.gateways;

import co.com.finova.gateway.model.audit.GatewayAttempt;

/** Publishes to audit.events. Must not block nor fail the request: the audit is never in the critical path. */
public interface AttemptPublisher {

    void publish(GatewayAttempt attempt);
}
