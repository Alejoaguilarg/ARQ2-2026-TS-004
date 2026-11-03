package co.com.finova.audit.usecase.support;

import co.com.finova.audit.model.common.gateways.TransactionRunner;

import java.util.function.Supplier;

/** Runs the work right away and counts the transactions. */
public class DirectTransactionRunner implements TransactionRunner {

    private int transactions;

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        transactions++;
        return work.get();
    }

    public int transactions() {
        return transactions;
    }
}
