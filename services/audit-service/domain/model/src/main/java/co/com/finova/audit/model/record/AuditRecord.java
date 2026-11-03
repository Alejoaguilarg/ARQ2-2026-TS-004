package co.com.finova.audit.model.record;

import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.common.Contract;
import co.com.finova.audit.model.common.Timestamps;

import java.time.Instant;

/**
 * One row of the append-only audit trail (spec §2 AuditRecord): the attempt, its position seq in the
 * single total order, when Audit stored it, and its link to the previous record. A record is never
 * changed once written.
 */
public record AuditRecord(long seq, AttemptRecorded attempt, Instant recordedAt, String prevHash, String hash) {

    public AuditRecord {
        Contract.require(seq >= 1, "seq must be >= 1: " + seq);
        Contract.requireNonNull(attempt, "attempt");
        Contract.requireNonNull(recordedAt, "recordedAt");
        requireHash(prevHash, "prevHash");
        requireHash(hash, "hash");
        recordedAt = Timestamps.toStoredPrecision(recordedAt);
    }

    /** The record that follows {@code previous} in the chain (F5.2). */
    public static AuditRecord append(ChainLink previous, AttemptRecorded attempt, Instant recordedAt) {
        Contract.requireNonNull(previous, "previous");
        Contract.requireNonNull(attempt, "attempt");
        Contract.requireNonNull(recordedAt, "recordedAt");
        long seq = previous.seq() + 1;
        Instant storedAt = Timestamps.toStoredPrecision(recordedAt);
        return new AuditRecord(seq, attempt, storedAt, previous.hash(),
                HashChain.hash(previous.hash(), seq, attempt, storedAt));
    }

    /** True when the stored hash still matches the content of the record and its prevHash (F5.5). */
    public boolean isIntact() {
        return hash.equals(HashChain.hash(prevHash, seq, attempt, recordedAt));
    }

    public ChainLink link() {
        return new ChainLink(seq, hash);
    }

    private static void requireHash(String value, String field) {
        Contract.requireNonNull(value, field);
        Contract.require(ChainLink.HASH_FORMAT.matcher(value).matches(),
                field + " must be 64 lowercase hex characters");
    }
}
