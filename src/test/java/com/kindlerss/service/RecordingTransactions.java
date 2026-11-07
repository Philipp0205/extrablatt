package com.kindlerss.service;

import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs callbacks directly but remembers whether one is running, so a test can
 * assert that a network fetch did not happen inside a transaction.
 */
class RecordingTransactions implements TransactionOperations {

    private boolean active;
    private int started;

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
        active = true;
        started++;
        try {
            return action.doInTransaction(new SimpleTransactionStatus());
        } finally {
            active = false;
        }
    }

    boolean active() {
        return active;
    }

    int started() {
        return started;
    }
}
