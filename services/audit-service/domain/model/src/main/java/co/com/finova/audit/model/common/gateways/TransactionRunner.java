package co.com.finova.audit.model.common.gateways;

import java.util.function.Supplier;

/**
 * Runs work in one local transaction of audit_db: the audit record and its audit_event_ids row commit
 * together or not at all (spec §2 AuditEventId). Any exception rolls everything back.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
