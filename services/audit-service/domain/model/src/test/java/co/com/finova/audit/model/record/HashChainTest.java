package co.com.finova.audit.model.record;

import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Golden values computed outside Java, with Python:
 * {@code sha256((prev_hash + json.dumps(record, sort_keys=True, separators=(',', ':'),
 * ensure_ascii=False)).encode()).hexdigest()}. If these tests break, every stored hash breaks too.
 */
class HashChainTest {

    static final AttemptRecorded SPEC_EXAMPLE = new AttemptRecorded(
            UUID.fromString("3f9a6c1e-7b2d-4c5e-8f9a-0b1c2d3e4f5a"), AttemptSource.TRANSFER, AttemptStage.REQUEST,
            AttemptOutcome.ACCEPTED, null, UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
            UUID.fromString("f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b"), "7e57d004-2b97-4e7a-b45f-5387367791cd",
            "150000.00", 202, UUID.fromString("5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11"),
            Instant.parse("2026-10-19T15:00:00Z"));
    static final Instant RECORDED_AT = Instant.parse("2026-10-19T15:00:00.040Z");

    private static final String SPEC_EXAMPLE_FORM = "{\"amount\":\"150000.00\","
            + "\"correlationId\":\"5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11\","
            + "\"eventId\":\"3f9a6c1e-7b2d-4c5e-8f9a-0b1c2d3e4f5a\",\"httpStatus\":202,"
            + "\"idempotencyKey\":\"7e57d004-2b97-4e7a-b45f-5387367791cd\","
            + "\"occurredAt\":\"2026-10-19T15:00:00.000000Z\",\"outcome\":\"ACCEPTED\",\"reason\":null,"
            + "\"recordedAt\":\"2026-10-19T15:00:00.040000Z\",\"seq\":10231,\"source\":\"TRANSFER\","
            + "\"stage\":\"REQUEST\",\"transferId\":\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\","
            + "\"userId\":\"f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b\"}";
    private static final String SPEC_EXAMPLE_HASH =
            "7e7b8093e73c79d8581588066f1067bf755d9a7a5fddc49146fdf472b936269f";

    @Test
    void genesisIsSixtyFourZeros() {
        assertEquals("0".repeat(64), HashChain.GENESIS_HASH);
    }

    @Test
    void canonicalFormSortsTheKeysAndHasNoWhitespace() {
        assertEquals(SPEC_EXAMPLE_FORM, HashChain.canonicalForm(10231, SPEC_EXAMPLE, RECORDED_AT));
    }

    @Test
    void hashIsSha256OfPrevHashFollowedByTheCanonicalForm() {
        assertEquals(SPEC_EXAMPLE_HASH, HashChain.hash(HashChain.GENESIS_HASH, 10231, SPEC_EXAMPLE, RECORDED_AT));
    }

    @Test
    void escapesStringsLikeJsonAndKeepsNonAsciiCharacters() {
        AttemptRecorded withReason = new AttemptRecorded(SPEC_EXAMPLE.eventId(), SPEC_EXAMPLE.source(),
                SPEC_EXAMPLE.stage(), SPEC_EXAMPLE.outcome(), "quote \" back \\ nl \n tab \t bell \u0007 ñ",
                SPEC_EXAMPLE.transferId(), SPEC_EXAMPLE.userId(), SPEC_EXAMPLE.idempotencyKey(),
                SPEC_EXAMPLE.amount(), null, SPEC_EXAMPLE.correlationId(), SPEC_EXAMPLE.occurredAt());

        assertEquals("6557cbac73785ede0f6415e281749d0c39ccda6c560441625f53a1ac0ebc2b7f",
                HashChain.hash(SPEC_EXAMPLE_HASH, 10232, withReason, RECORDED_AT));
    }

    @Test
    void anyChangeChangesTheHash() {
        String original = HashChain.hash(HashChain.GENESIS_HASH, 10231, SPEC_EXAMPLE, RECORDED_AT);
        AttemptRecorded otherAmount = new AttemptRecorded(SPEC_EXAMPLE.eventId(), SPEC_EXAMPLE.source(),
                SPEC_EXAMPLE.stage(), SPEC_EXAMPLE.outcome(), null, SPEC_EXAMPLE.transferId(),
                SPEC_EXAMPLE.userId(), SPEC_EXAMPLE.idempotencyKey(), "150000.01", 202,
                SPEC_EXAMPLE.correlationId(), SPEC_EXAMPLE.occurredAt());

        assertNotEquals(original, HashChain.hash(HashChain.GENESIS_HASH, 10231, otherAmount, RECORDED_AT));
        assertNotEquals(original, HashChain.hash(HashChain.GENESIS_HASH, 10232, SPEC_EXAMPLE, RECORDED_AT));
        assertNotEquals(original, HashChain.hash("1".repeat(64), 10231, SPEC_EXAMPLE, RECORDED_AT));
        assertNotEquals(original, HashChain.hash(HashChain.GENESIS_HASH, 10231, SPEC_EXAMPLE,
                RECORDED_AT.plusNanos(1_000)));
    }
}
