package co.com.finova.audit.usecase.verifychain;

import co.com.finova.audit.model.query.InvalidAuditQueryException;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.ChainLink;
import co.com.finova.audit.model.record.HashChain;
import co.com.finova.audit.model.record.gateways.AuditRecordRepository;
import co.com.finova.audit.model.verification.ChainVerification;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * GET /audit/verify (spec §3.11, F5.5). Walks the range in seq order, in batches, and checks every link:
 * <ol>
 *   <li>no seq is missing (a deleted row breaks the sequence);</li>
 *   <li>prevHash equals the stored hash of the previous record (genesis for seq 1);</li>
 *   <li>SHA-256(prevHash || canonical form) still equals the stored hash.</li>
 * </ol>
 * The whole range is always walked, so checkedRecords counts every record in it; firstInvalidSeq is the
 * first broken link. A range that starts after seq 1 is anchored on the stored hash of record fromSeq - 1.
 */
public class VerifyAuditChainUseCase {

    static final int BATCH_SIZE = 1_000;

    private final AuditRecordRepository records;
    private final Clock clock;

    public VerifyAuditChainUseCase(AuditRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    /** fromSeq defaults to the genesis (1) and toSeq to the last record; both are inclusive. */
    public ChainVerification handle(Long fromSeq, Long toSeq) {
        validate(fromSeq, toSeq);
        Optional<ChainLink> last = records.findLastLink();
        long from = fromSeq == null ? 1 : fromSeq;
        long to = toSeq != null ? toSeq : last.map(ChainLink::seq).orElse(0L);
        if (from > to) {
            return new ChainVerification(true, 0, null, clock.instant());
        }

        Walk walk = new Walk(from, anchor(from));
        long cursor = from;
        while (cursor <= to) {
            List<AuditRecord> batch = records.findRange(cursor, to, BATCH_SIZE);
            if (batch.isEmpty()) {
                walk.missing(cursor);
                break;
            }
            batch.forEach(walk::check);
            cursor = batch.get(batch.size() - 1).seq() + 1;
        }
        return new ChainVerification(walk.firstInvalidSeq == null, walk.checked, walk.firstInvalidSeq,
                clock.instant());
    }

    /** The hash record fromSeq must link to; null when record fromSeq - 1 is missing. */
    private String anchor(long from) {
        if (from == 1) {
            return HashChain.GENESIS_HASH;
        }
        return records.findLink(from - 1).map(ChainLink::hash).orElse(null);
    }

    private static void validate(Long fromSeq, Long toSeq) {
        if (fromSeq != null && fromSeq < 1) {
            throw new InvalidAuditQueryException("fromSeq", "must be >= 1");
        }
        if (toSeq != null && toSeq < 1) {
            throw new InvalidAuditQueryException("toSeq", "must be >= 1");
        }
        if (fromSeq != null && toSeq != null && toSeq < fromSeq) {
            throw new InvalidAuditQueryException("toSeq", "must not be lower than fromSeq");
        }
    }

    /** State of one walk over the chain. */
    private static final class Walk {

        private long expectedSeq;
        private String expectedPrevHash;
        private long checked;
        private Long firstInvalidSeq;

        private Walk(long from, String anchor) {
            this.expectedSeq = from;
            this.expectedPrevHash = anchor;
            if (anchor == null) {
                // Record from - 1 is gone: the link of the first record in the range cannot hold.
                firstInvalidSeq = from - 1;
            }
        }

        private void check(AuditRecord record) {
            if (record.seq() != expectedSeq) {
                invalid(expectedSeq);
            } else if (!record.prevHash().equals(expectedPrevHash) || !record.isIntact()) {
                invalid(record.seq());
            }
            checked++;
            expectedSeq = record.seq() + 1;
            expectedPrevHash = record.hash();
        }

        private void missing(long seq) {
            invalid(seq);
        }

        private void invalid(long seq) {
            if (firstInvalidSeq == null) {
                firstInvalidSeq = seq;
            }
        }
    }
}
