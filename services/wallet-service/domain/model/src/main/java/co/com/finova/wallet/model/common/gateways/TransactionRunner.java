package co.com.finova.wallet.model.common.gateways;

import java.util.function.Supplier;

/**
 * Runs work in one local transaction (spec §4.7): the effect, its ledger entry, its processed_messages
 * row and its outbox rows commit together or not at all. Any exception rolls everything back.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
