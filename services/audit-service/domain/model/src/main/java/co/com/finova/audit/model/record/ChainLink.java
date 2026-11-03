package co.com.finova.audit.model.record;

import co.com.finova.audit.model.common.Contract;

import java.util.regex.Pattern;

/** The position and hash of one record of the chain; the next record links to it through prevHash. */
public record ChainLink(long seq, String hash) {

    // Declared before GENESIS: static fields are initialized in order and the constructor uses it.
    static final Pattern HASH_FORMAT = Pattern.compile("^[a-f0-9]{64}$");

    /** What the first record (seq 1) links to: seq 0 and 64 zeros (spec §2 AuditRecord). */
    public static final ChainLink GENESIS = new ChainLink(0, HashChain.GENESIS_HASH);

    public ChainLink {
        Contract.require(seq >= 0, "seq must be >= 0: " + seq);
        Contract.requireNonNull(hash, "hash");
        Contract.require(HASH_FORMAT.matcher(hash).matches(), "hash must be 64 lowercase hex characters");
    }
}
