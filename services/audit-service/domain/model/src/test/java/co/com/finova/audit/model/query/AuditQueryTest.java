package co.com.finova.audit.model.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditQueryTest {

    private static final UUID TRANSFER_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final Instant FROM = Instant.parse("2026-10-19T00:00:00Z");

    @Test
    void appliesTheDefaultPageAndSize() {
        AuditQuery query = AuditQuery.of(TRANSFER_ID, null, null, null, null, null);

        assertEquals(0, query.page());
        assertEquals(50, query.size());
        assertEquals(0, query.offset());
    }

    @Test
    void requiresTransferIdUserIdOrFrom() {
        InvalidAuditQueryException e = assertThrows(InvalidAuditQueryException.class,
                () -> AuditQuery.of(null, null, null, Instant.now(), null, null));

        assertEquals("transferId", e.field());
    }

    @Test
    void acceptsAnyOfTheThreeFilters() {
        AuditQuery.of(TRANSFER_ID, null, null, null, null, null);
        AuditQuery.of(null, UUID.randomUUID(), null, null, null, null);
        AuditQuery.of(null, null, FROM, null, null, null);
    }

    @Test
    void sizeIsBetweenOneAndTwoHundred() {
        assertEquals("size", assertThrows(InvalidAuditQueryException.class,
                () -> AuditQuery.of(TRANSFER_ID, null, null, null, 0, 0)).field());
        assertEquals("size", assertThrows(InvalidAuditQueryException.class,
                () -> AuditQuery.of(TRANSFER_ID, null, null, null, 0, 201)).field());
        assertEquals(200, AuditQuery.of(TRANSFER_ID, null, null, null, 0, 200).size());
    }

    @Test
    void pageIsNotNegative() {
        assertEquals("page", assertThrows(InvalidAuditQueryException.class,
                () -> AuditQuery.of(TRANSFER_ID, null, null, null, -1, 10)).field());
    }

    @Test
    void toCannotBeBeforeFrom() {
        assertEquals("to", assertThrows(InvalidAuditQueryException.class,
                () -> AuditQuery.of(null, null, FROM, FROM.minusSeconds(1), null, null)).field());
        AuditQuery.of(null, null, FROM, FROM, null, null);
    }

    @Test
    void offsetIsPageTimesSize() {
        assertEquals(400L, AuditQuery.of(TRANSFER_ID, null, null, null, 2, 200).offset());
    }
}
