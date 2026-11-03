package co.com.finova.audit.model.verification;

import java.time.Instant;

/**
 * Result of GET /audit/verify (spec §3.11, F5.5). firstInvalidSeq is null when the range is intact;
 * otherwise it is the first seq whose link is broken: its content no longer matches its hash, its
 * prevHash does not match the previous record, or the record is missing from the sequence.
 */
public record ChainVerification(boolean valid, long checkedRecords, Long firstInvalidSeq, Instant verifiedAt) {
}
