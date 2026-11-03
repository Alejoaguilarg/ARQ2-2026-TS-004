package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.idempotency.IdempotencyRecord;
import co.com.finova.gateway.model.idempotency.IdempotencyStoreUnavailableException;
import co.com.finova.gateway.model.idempotency.gateways.IdempotencyStore;
import co.com.finova.gateway.usecase.idempotency.IdempotencyUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyFilterTest {

    private static final String USER = "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c";
    private static final String KEY = "3f1e9a7c-5b2d-4c8e-9f0a-1b2c3d4e5f60";
    private static final String BODY = "{\"recipientUserId\":\"r-1\",\"amount\":\"150000.00\",\"currency\":\"COP\"}";

    private final InMemoryStore store = new InMemoryStore();
    private final IdempotencyFilter filter = new IdempotencyFilter(new IdempotencyUseCase(store));

    private int upstreamCalls;
    private String forwardedBody;
    private int upstreamStatus = 202;
    private final FilterChain transfer = (request, response) -> {
        upstreamCalls++;
        forwardedBody = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ((HttpServletResponse) response).setStatus(upstreamStatus);
        response.getWriter().write("{\"transferId\":\"t-1\",\"status\":\"PENDING\"}");
    };

    @Test
    void f31_missingKeyIs400AndNotRouted() throws Exception {
        MockHttpServletResponse response = send(post(null, BODY));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void f31_keyThatIsNotAUuidIs400() throws Exception {
        assertThat(send(post("abc", BODY)).getStatus()).isEqualTo(400);
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void unauthenticatedRequestIs401AndNotRouted() throws Exception {
        MockHttpServletRequest request = post(KEY, BODY);
        request.removeAttribute(AuthenticatedUser.ATTRIBUTE);

        assertThat(send(request).getStatus()).isEqualTo(401);
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void f32_firstRequestIsRoutedWithItsBodyAndStoredAsCompleted() throws Exception {
        MockHttpServletResponse response = send(post(KEY, BODY));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getContentAsString()).contains("t-1");
        assertThat(forwardedBody).isEqualTo(BODY);
        assertThat(store.records.get(USER + ":" + KEY).state()).isEqualTo(IdempotencyRecord.State.COMPLETED);
    }

    @Test
    void f33_duplicateWhileInProgressIs409AndNotRouted() throws Exception {
        send(post(KEY, BODY));
        store.records.put(USER + ":" + KEY, IdempotencyRecord.inProgress(store.records.get(USER + ":" + KEY).requestHash()));

        MockHttpServletResponse response = send(post(KEY, BODY));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_IN_PROGRESS");
        assertThat(upstreamCalls).isOne();
    }

    @Test
    void f33_tenTapsWithTheSameKeyReachTransferOnce() throws Exception {
        for (int tap = 0; tap < 10; tap++) {
            send(post(KEY, BODY));
        }

        assertThat(upstreamCalls).isOne();
    }

    @Test
    void f34_retryAfterCompletionReplaysTheStoredResponse() throws Exception {
        send(post(KEY, BODY));

        MockHttpServletResponse replay = send(post(KEY, BODY));

        assertThat(replay.getStatus()).isEqualTo(202);
        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsString()).isEqualTo("{\"transferId\":\"t-1\",\"status\":\"PENDING\"}");
        assertThat(upstreamCalls).isOne();
    }

    @Test
    void sameJsonWithOtherKeyOrderAndSpacingIsTheSameRequest() throws Exception {
        send(post(KEY, BODY));

        String reordered = "{ \"currency\": \"COP\", \"amount\": \"150000.00\", \"recipientUserId\": \"r-1\" }";

        assertThat(send(post(KEY, reordered)).getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void f35_sameKeyWithDifferentBodyIs422() throws Exception {
        send(post(KEY, BODY));

        MockHttpServletResponse response = send(post(KEY, BODY.replace("150000.00", "999999.00")));

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(upstreamCalls).isOne();
    }

    @Test
    void f36_storeUnavailableIs503AndNotRouted() throws Exception {
        store.down = true;

        MockHttpServletResponse response = send(post(KEY, BODY));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_STORE_UNAVAILABLE");
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void f38_upstream5xxDeletesTheKeyAndTheRetryIsRouted() throws Exception {
        upstreamStatus = 503;
        assertThat(send(post(KEY, BODY)).getStatus()).isEqualTo(503);
        assertThat(store.records).doesNotContainKey(USER + ":" + KEY);

        upstreamStatus = 202;
        assertThat(send(post(KEY, BODY)).getStatus()).isEqualTo(202);
        assertThat(upstreamCalls).isEqualTo(2);
    }

    @Test
    void f38_noUpstreamAnswerDeletesTheKeyAndRethrows() {
        FilterChain unreachable = (request, response) -> {
            throw new IOException("connection refused");
        };

        assertThatThrownBy(() -> filter.doFilter(post(KEY, BODY), new MockHttpServletResponse(), unreachable))
                .isInstanceOf(IOException.class);
        assertThat(store.records).doesNotContainKey(USER + ":" + KEY);
    }

    @Test
    void oversizedBodyIs413AndNotRouted() throws Exception {
        assertThat(send(post(KEY, "x".repeat(16 * 1024 + 1))).getStatus()).isEqualTo(413);
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void otherRoutesAreNotFiltered() throws Exception {
        MockHttpServletRequest balance = new MockHttpServletRequest("GET", "/wallets/me/balance");

        filter.doFilter(balance, new MockHttpServletResponse(), transfer);

        assertThat(upstreamCalls).isOne();
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, transfer);
        return response;
    }

    private static MockHttpServletRequest post(String key, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfers");
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, USER);
        if (key != null) {
            request.addHeader("Idempotency-Key", key);
        }
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    static final class InMemoryStore implements IdempotencyStore {

        private final Map<String, IdempotencyRecord> records = new HashMap<>();
        private boolean down;

        @Override
        public boolean createIfAbsent(String userId, String idempotencyKey, IdempotencyRecord record) {
            check();
            return records.putIfAbsent(userId + ":" + idempotencyKey, record) == null;
        }

        @Override
        public Optional<IdempotencyRecord> find(String userId, String idempotencyKey) {
            check();
            return Optional.ofNullable(records.get(userId + ":" + idempotencyKey));
        }

        @Override
        public void save(String userId, String idempotencyKey, IdempotencyRecord record) {
            check();
            records.put(userId + ":" + idempotencyKey, record);
        }

        @Override
        public void delete(String userId, String idempotencyKey) {
            check();
            records.remove(userId + ":" + idempotencyKey);
        }

        private void check() {
            if (down) {
                throw new IdempotencyStoreUnavailableException(new IllegalStateException("redis down"));
            }
        }
    }
}
