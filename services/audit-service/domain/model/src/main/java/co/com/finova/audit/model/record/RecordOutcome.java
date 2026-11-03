package co.com.finova.audit.model.record;

import java.util.UUID;

/** What happened to one AttemptRecorded: stored with a new seq, or ignored as a duplicate (F5.3). */
public record RecordOutcome(UUID eventId, boolean duplicate, Long seq) {

    public static RecordOutcome recorded(UUID eventId, long seq) {
        return new RecordOutcome(eventId, false, seq);
    }

    public static RecordOutcome ignoredDuplicate(UUID eventId) {
        return new RecordOutcome(eventId, true, null);
    }
}
