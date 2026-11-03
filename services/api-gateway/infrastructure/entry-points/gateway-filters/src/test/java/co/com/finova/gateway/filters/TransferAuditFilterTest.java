package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.audit.GatewayAttempt;
import co.com.finova.gateway.model.audit.GatewayAttempt.Outcome;
import co.com.finova.gateway.usecase.audit.RecordAttemptUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec §3.1 step 6: the Gateway records what it answered itself on POST /transfers, and upstream failures. */
class TransferAuditFilterTest {

    private static final String USER = "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c";
    private static final String KEY = "3F1E9A7C-5B2D-4C8E-9F0A-1B2C3D4E5F60";
    private static final String TRANSFER = "9b2c4d6e-8f0a-4b1c-9d3e-5f7a9b1c3d5e";

    private final List<GatewayAttempt> published = new ArrayList<>();
    private final TransferAuditFilter filter = new TransferAuditFilter(new RecordAttemptUseCase(published::add));

    @Test
    void recordsARejectionWithTheUserTheKeyAndTheCorrelationId() throws Exception {
        MockHttpServletRequest request = post();
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, USER);

        MockHttpServletResponse response = send(request, (req, res) -> GatewayResponses.error(
                (HttpServletRequest) req, (HttpServletResponse) res, 403, "FINANCIAL_SESSION_REQUIRED", "x"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(published).singleElement().satisfies(attempt -> {
            assertThat(attempt.outcome()).isEqualTo(Outcome.REJECTED);
            assertThat(attempt.reason()).isEqualTo("FINANCIAL_SESSION_REQUIRED");
            assertThat(attempt.httpStatus()).isEqualTo(403);
            assertThat(attempt.userId()).isEqualTo(USER);
            assertThat(attempt.idempotencyKey()).isEqualTo(KEY.toLowerCase());
            assertThat(attempt.correlationId()).isNotBlank();
        });
    }

    @Test
    void anUnauthenticatedRequestHasNoUser() throws Exception {
        send(post(), (req, res) -> GatewayResponses.error(
                (HttpServletRequest) req, (HttpServletResponse) res, 401, "UNAUTHORIZED", "x"));

        assertThat(published).singleElement().satisfies(attempt -> assertThat(attempt.userId()).isNull());
    }

    @Test
    void aKeyThatIsNotAUuidIsNotRecorded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfers");
        request.addHeader("Idempotency-Key", "not-a-uuid");

        send(request, (req, res) -> GatewayResponses.error(
                (HttpServletRequest) req, (HttpServletResponse) res, 400, "IDEMPOTENCY_KEY_REQUIRED", "x"));

        assertThat(published).singleElement().satisfies(attempt -> {
            assertThat(attempt.reason()).isEqualTo("MISSING_KEY");
            assertThat(attempt.idempotencyKey()).isNull();
        });
    }

    @Test
    void recordsAReplayWithTheTransferItReplayed() throws Exception {
        send(post(), (req, res) -> {
            req.setAttribute(IdempotencyFilter.REPLAY_ATTRIBUTE, "{\"transferId\":\"" + TRANSFER + "\"}");
            ((HttpServletResponse) res).setStatus(202);
        });

        assertThat(published).singleElement().satisfies(attempt -> {
            assertThat(attempt.reason()).isEqualTo("DUPLICATE_REPLAYED");
            assertThat(attempt.transferId()).isEqualTo(TRANSFER);
        });
    }

    @Test
    void aReplayedBodyWithoutAValidTransferIdKeepsItNull() throws Exception {
        send(post(), (req, res) -> req.setAttribute(IdempotencyFilter.REPLAY_ATTRIBUTE, "not json"));

        assertThat(published).singleElement().satisfies(attempt -> assertThat(attempt.transferId()).isNull());
    }

    @Test
    void leavesWhatTransferAnsweredToTransfer() throws Exception {
        send(post(), (req, res) -> ((HttpServletResponse) res).setStatus(202));

        assertThat(published).isEmpty();
    }

    @Test
    void recordsAnErrorNobodyAnsweredAsFailedAndRethrowsIt() {
        assertThatThrownBy(() -> send(post(), (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(published).singleElement().satisfies(attempt -> {
            assertThat(attempt.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(attempt.reason()).isEqualTo("INTERNAL_ERROR");
            assertThat(attempt.httpStatus()).isEqualTo(500);
        });
    }

    @Test
    void aBrokenAuditNeverChangesTheAnswer() throws Exception {
        TransferAuditFilter broken = new TransferAuditFilter(new RecordAttemptUseCase(attempt -> {
            throw new IllegalStateException("audit down");
        }));
        MockHttpServletResponse response = new MockHttpServletResponse();

        broken.doFilter(post(), response, (req, res) -> GatewayResponses.error(
                (HttpServletRequest) req, (HttpServletResponse) res, 409, "IDEMPOTENCY_IN_PROGRESS", "x"));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void otherRoutesAreNotAudited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/transfers/" + TRANSFER);

        send(request, (req, res) -> GatewayResponses.error(
                (HttpServletRequest) req, (HttpServletResponse) res, 401, "UNAUTHORIZED", "x"));

        assertThat(published).isEmpty();
    }

    private static MockHttpServletRequest post() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfers");
        request.addHeader("Idempotency-Key", KEY);
        return request;
    }

    private MockHttpServletResponse send(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
