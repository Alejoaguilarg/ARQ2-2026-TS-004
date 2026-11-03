package co.com.finova.gateway.usecase.audit;

import co.com.finova.gateway.model.audit.GatewayAttempt;
import co.com.finova.gateway.model.audit.GatewayAttempt.Outcome;
import co.com.finova.gateway.usecase.audit.RecordAttemptUseCase.Answer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Gateway rows of spec §4.8. */
class RecordAttemptUseCaseTest {

    private static final String USER = "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c";
    private static final String KEY = "0b7f4a52-9c1e-4d3a-8f6b-2e5c7d9a1b30";
    private static final String CORRELATION = "c0ffee00-0000-4000-8000-000000000001";

    private final List<GatewayAttempt> published = new ArrayList<>();
    private final RecordAttemptUseCase useCase = new RecordAttemptUseCase(published::add);

    @ParameterizedTest
    @CsvSource({
            "400, IDEMPOTENCY_KEY_REQUIRED,      REJECTED,  MISSING_KEY",
            "422, IDEMPOTENCY_KEY_REUSED,        REJECTED,  KEY_REUSED",
            "409, IDEMPOTENCY_IN_PROGRESS,       DUPLICATE, IN_PROGRESS_CONFLICT",
            "503, IDEMPOTENCY_STORE_UNAVAILABLE, REJECTED,  IDEMPOTENCY_STORE_UNAVAILABLE",
            "401, TOKEN_REVOKED,                 REJECTED,  TOKEN_REVOKED",
            "403, FINANCIAL_SESSION_REQUIRED,    REJECTED,  FINANCIAL_SESSION_REQUIRED",
            "429, RATE_LIMIT_EXCEEDED,           REJECTED,  RATE_LIMIT_EXCEEDED",
            "502, UPSTREAM_UNAVAILABLE,          FAILED,    UPSTREAM_UNAVAILABLE",
            "504, UPSTREAM_UNAVAILABLE,          FAILED,    UPSTREAM_UNAVAILABLE"})
    void recordsWhatTheGatewayAnsweredItself(int status, String code, Outcome outcome, String reason) {
        useCase.record(new Answer(status, code, false, null, USER, KEY, CORRELATION));

        assertThat(published).singleElement().satisfies(attempt -> {
            assertThat(attempt.outcome()).isEqualTo(outcome);
            assertThat(attempt.reason()).isEqualTo(reason);
            assertThat(attempt.httpStatus()).isEqualTo(status);
            assertThat(attempt.userId()).isEqualTo(USER);
            assertThat(attempt.idempotencyKey()).isEqualTo(KEY);
            assertThat(attempt.correlationId()).isEqualTo(CORRELATION);
            assertThat(attempt.eventId()).isNotNull();
            assertThat(attempt.occurredAt()).isNotNull();
        });
    }

    @Test
    void recordsAReplayWithItsTransfer() {
        useCase.record(new Answer(202, null, true, "3f2a1b0c-0000-4000-8000-000000000002", USER, KEY, CORRELATION));

        assertThat(published).singleElement().satisfies(attempt -> {
            assertThat(attempt.outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(attempt.reason()).isEqualTo("DUPLICATE_REPLAYED");
            assertThat(attempt.transferId()).isEqualTo("3f2a1b0c-0000-4000-8000-000000000002");
        });
    }

    @Test
    void recordsATransfer5xxAsFailed() {
        useCase.record(new Answer(500, null, false, null, USER, KEY, CORRELATION));

        assertThat(published).singleElement().satisfies(attempt -> {
            assertThat(attempt.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(attempt.reason()).isEqualTo("INTERNAL_ERROR");
        });
    }

    @ParameterizedTest
    @CsvSource({"202", "400", "422"})
    void leavesWhatTransferAnsweredToTransfer(int status) {
        useCase.record(new Answer(status, null, false, null, USER, KEY, CORRELATION));

        assertThat(published).isEmpty();
    }
}
