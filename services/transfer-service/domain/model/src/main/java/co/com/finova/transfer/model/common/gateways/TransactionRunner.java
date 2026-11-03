package co.com.finova.transfer.model.common.gateways;

import java.util.function.Supplier;

/**
 * Runs work in one local transaction of transfer_db (spec §1.4, §4.6): the state change, its processed_messages
 * row and its outbox rows commit together or not at all. Any exception rolls everything back.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
