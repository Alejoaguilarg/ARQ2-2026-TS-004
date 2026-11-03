package co.com.finova.transfer.api.transfer;

import co.com.finova.transfer.api.config.CorrelationIdFilter;
import co.com.finova.transfer.api.error.ApiExceptionHandler;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.transfer.FailureReason;
import co.com.finova.transfer.model.transfer.IdempotencyKeyReusedException;
import co.com.finova.transfer.model.transfer.SelfTransferNotAllowedException;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferNotFoundException;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.usecase.createtransfer.CreateTransferUseCase;
import co.com.finova.transfer.usecase.gettransfer.GetTransferUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §3.8, §3.9 and the error model of §3.2. */
class TransferControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T15:00:00Z");
    private static final String BODY = """
            {"recipientUserId":"b3c9a1e2-5d4f-4a6b-8c7d-9e0f1a2b3c4d","amount":"150000.00","currency":"COP",
             "description":"Dinner"}""";

    private final CreateTransferUseCase createTransfer = mock(CreateTransferUseCase.class);
    private final GetTransferUseCase getTransfer = mock(GetTransferUseCase.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TransferController(createTransfer, getTransfer))
            .setControllerAdvice(new ApiExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
            .addFilters(new CorrelationIdFilter())
            .build();

    private final UUID sender = UUID.fromString("f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b");
    private final UUID recipient = UUID.fromString("b3c9a1e2-5d4f-4a6b-8c7d-9e0f1a2b3c4d");
    private final String key = "7e57d004-2b97-4e7a-b45f-5387367791cd";
    private final String correlationId = "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11";

    private Transfer transfer() {
        return Transfer.create(new TransferRequest(sender, recipient, Money.parse("150000.00"), Currency.COP, "Dinner",
                UUID.fromString(key), "a".repeat(64)), UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
                UUID.fromString(correlationId), UUID.randomUUID(), NOW);
    }

    private ResultActions post(String body, String idempotencyKey) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transfers")
                .header("X-User-Id", sender.toString())
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    @Test
    void anAcceptedTransferAnswers202WithLocation_s3_8() throws Exception {
        when(createTransfer.handle(any(), any())).thenReturn(new CreateTransferUseCase.Result(transfer(), false));

        post(BODY, key)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/transfers/a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
                // The Gateway returns it; a second copy from Transfer would duplicate the header.
                .andExpect(header().doesNotExist("X-Correlation-Id"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.transferId").value("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.statusUrl").value("/transfers/a1b2c3d4-e5f6-7890-abcd-ef1234567890"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-19T15:00:00Z"));

        ArgumentCaptor<TransferRequest> request = ArgumentCaptor.forClass(TransferRequest.class);
        verify(createTransfer).handle(request.capture(), eq(UUID.fromString(correlationId)));
        assertThat(request.getValue().senderUserId()).isEqualTo(sender);
        assertThat(request.getValue().recipientUserId()).isEqualTo(recipient);
        assertThat(request.getValue().amount()).isEqualTo(Money.parse("150000.00"));
        assertThat(request.getValue().description()).isEqualTo("Dinner");
        assertThat(request.getValue().idempotencyKey()).isEqualTo(UUID.fromString(key));
        assertThat(request.getValue().requestHash()).matches("^[a-f0-9]{64}$");
    }

    @Test
    void aReplayCarriesIdempotentReplayed_F3_7() throws Exception {
        when(createTransfer.handle(any(), any())).thenReturn(new CreateTransferUseCase.Result(transfer(), true));

        post(BODY, key).andExpect(status().isAccepted()).andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void theRequestHashIgnoresKeyOrderAndWhitespace() {
        UUID id = UUID.randomUUID();
        String reordered = """
                {"currency":"COP","description":"Dinner","amount":"150000.00",
                   "recipientUserId":"b3c9a1e2-5d4f-4a6b-8c7d-9e0f1a2b3c4d"}""";

        assertThat(TransferBody.parse(reordered.getBytes(), sender, id).requestHash())
                .isEqualTo(TransferBody.parse(BODY.getBytes(), sender, id).requestHash());
        assertThat(TransferBody.parse(BODY.replace("Dinner", "Lunch").getBytes(), sender, id).requestHash())
                .isNotEqualTo(TransferBody.parse(BODY.getBytes(), sender, id).requestHash());
    }

    @Test
    void anInvalidBodyAnswers400WithTheFieldsAndIsAudited() throws Exception {
        post("""
                {"recipientUserId":"nope","amount":150000,"currency":"USD","description":7}""", key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").value(correlationId))
                .andExpect(jsonPath("$.timestamp").value("2026-10-19T15:00:00Z"))
                .andExpect(jsonPath("$.details.recipientUserId").exists())
                .andExpect(jsonPath("$.details.amount").exists())
                .andExpect(jsonPath("$.details.currency").exists())
                .andExpect(jsonPath("$.details.description").exists());

        verify(createTransfer).rejectInvalid(sender, key, UUID.fromString(correlationId));
        verify(createTransfer, never()).handle(any(), any());
    }

    @Test
    void aZeroAmountOrALongDescriptionIsInvalid() throws Exception {
        post(BODY.replace("150000.00", "0.00"), key).andExpect(jsonPath("$.details.amount").exists());
        post(BODY.replace("Dinner", "x".repeat(141)), key).andExpect(jsonPath("$.details.description").exists());
    }

    @Test
    void aBodyThatIsNotAJsonObjectIsInvalid() throws Exception {
        post("[1,2]", key).andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.body").exists());
        post("not json", key).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transfers")
                        .header("X-User-Id", sender.toString()).header("Idempotency-Key", key))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRequestWithoutDescriptionIsValid() throws Exception {
        when(createTransfer.handle(any(), any())).thenReturn(new CreateTransferUseCase.Result(transfer(), false));

        post("""
                {"recipientUserId":"b3c9a1e2-5d4f-4a6b-8c7d-9e0f1a2b3c4d","amount":"1.00","currency":"COP",
                 "description":null}""", key).andExpect(status().isAccepted());
    }

    @Test
    void aMissingIdempotencyKeyAnswers400() throws Exception {
        post(BODY, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        post(BODY, "not-a-uuid").andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        verify(createTransfer).rejectInvalid(sender, "not-a-uuid", UUID.fromString(correlationId));
    }

    @Test
    void useCaseRejectionsMapToTheirCodes() throws Exception {
        doThrow(new SelfTransferNotAllowedException()).when(createTransfer).handle(any(), any());
        post(BODY, key).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_TRANSFER_NOT_ALLOWED"));

        doThrow(new IdempotencyKeyReusedException()).when(createTransfer).handle(any(), any());
        post(BODY, key).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        doThrow(new IllegalStateException("db down")).when(createTransfer).handle(any(), any());
        post(BODY, key).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error."));
    }

    @Test
    void aMissingUserHeaderIsAGatewayFault() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transfers")
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        mvc.perform(get("/transfers/" + UUID.randomUUID()).header("X-User-Id", "nobody"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void aDirectCallWithoutCorrelationIdGetsANewOne() throws Exception {
        when(createTransfer.handle(any(), any())).thenReturn(new CreateTransferUseCase.Result(transfer(), false));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transfers")
                        .header("X-User-Id", sender.toString()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());

        // The new id is used for the logs and the messages of this transfer.
        verify(createTransfer).handle(any(), org.mockito.ArgumentMatchers.notNull());
    }

    @Test
    void theViewHidesTheSagaInternals_s3_9() throws Exception {
        Transfer reversed = transfer().debited(UUID.randomUUID(), NOW.plusSeconds(1))
                .compensating(FailureReason.CREDIT_TIMEOUT, UUID.randomUUID(), NOW.plusSeconds(31))
                .reversed(NOW.plusSeconds(32));
        when(getTransfer.handle(reversed.transferId(), recipient)).thenReturn(reversed);

        mvc.perform(get("/transfers/" + reversed.transferId()).header("X-User-Id", recipient.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(reversed.transferId().toString()))
                .andExpect(jsonPath("$.senderUserId").value(sender.toString()))
                .andExpect(jsonPath("$.recipientUserId").value(recipient.toString()))
                .andExpect(jsonPath("$.amount").value("150000.00"))
                .andExpect(jsonPath("$.currency").value("COP"))
                .andExpect(jsonPath("$.description").value("Dinner"))
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.failureReason").value("CREDIT_TIMEOUT"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-19T15:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-19T15:00:32Z"))
                .andExpect(jsonPath("$.completedAt").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.requestHash").doesNotExist())
                .andExpect(jsonPath("$.creditAttempts").doesNotExist())
                .andExpect(jsonPath("$.correlationId").doesNotExist());
    }

    @Test
    void aCompletedTransferShowsCompletedAt() throws Exception {
        Transfer completed = transfer().debited(UUID.randomUUID(), NOW).completed(NOW.plusSeconds(1));
        when(getTransfer.handle(completed.transferId(), sender)).thenReturn(completed);

        mvc.perform(get("/transfers/" + completed.transferId()).header("X-User-Id", sender.toString()))
                .andExpect(jsonPath("$.completedAt").value("2026-10-19T15:00:01Z"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void anUnknownOrHiddenTransferAnswers404() throws Exception {
        UUID id = UUID.randomUUID();
        when(getTransfer.handle(id, sender)).thenThrow(new TransferNotFoundException(id));

        mvc.perform(get("/transfers/" + id).header("X-User-Id", sender.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        mvc.perform(get("/transfers/not-a-uuid").header("X-User-Id", sender.toString()))
                .andExpect(status().isNotFound());
    }
}
