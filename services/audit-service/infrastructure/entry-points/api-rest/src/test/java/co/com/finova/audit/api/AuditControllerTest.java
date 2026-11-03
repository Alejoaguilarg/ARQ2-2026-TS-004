package co.com.finova.audit.api;

import co.com.finova.audit.api.error.ApiExceptionHandler;
import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;
import co.com.finova.audit.model.query.AuditPage;
import co.com.finova.audit.model.query.AuditQuery;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.ChainLink;
import co.com.finova.audit.model.record.gateways.AuditRecordRepository;
import co.com.finova.audit.usecase.queryrecords.QueryAuditRecordsUseCase;
import co.com.finova.audit.usecase.verifychain.VerifyAuditChainUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T16:00:00Z");
    private static final String TRANSFER_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

    private AuditRecordRepository records;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        records = mock(AuditRecordRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuditController controller = new AuditController(new QueryAuditRecordsUseCase(records),
                new VerifyAuditChainUseCase(records, clock));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(clock))
                .build();
    }

    @Test
    void anAuditorGetsTheRecordsOfATransfer() throws Exception {
        AuditRecord record = AuditRecord.append(ChainLink.GENESIS, attempt(),
                Instant.parse("2026-10-19T15:00:00.040Z"));
        when(records.find(any(AuditQuery.class))).thenReturn(new AuditPage(0, 50, 1, List.of(record)));

        mvc.perform(get("/audit/records").param("transferId", TRANSFER_ID).header("X-User-Roles", "USER,AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].seq").value(1))
                .andExpect(jsonPath("$.items[0].source").value("TRANSFER"))
                .andExpect(jsonPath("$.items[0].transferId").value(TRANSFER_ID))
                .andExpect(jsonPath("$.items[0].amount").value("150000.00"))
                .andExpect(jsonPath("$.items[0].httpStatus").value(202))
                .andExpect(jsonPath("$.items[0].recordedAt").value("2026-10-19T15:00:00.040Z"))
                .andExpect(jsonPath("$.items[0].prevHash").value("0".repeat(64)))
                .andExpect(jsonPath("$.items[0].hash").value(record.hash()));
    }

    @Test
    void aCallerWithoutTheAuditorRoleGets403() throws Exception {
        mvc.perform(get("/audit/records").param("transferId", TRANSFER_ID).header("X-User-Roles", "USER")
                        .header("X-Correlation-Id", "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"))
                .andExpect(jsonPath("$.correlationId").value("5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11"))
                .andExpect(jsonPath("$.timestamp").value(NOW.toString()));
        mvc.perform(get("/audit/verify"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        verify(records, never()).find(any(AuditQuery.class));
    }

    @Test
    void aRoleThatOnlyContainsTheWordAuditorIsNotEnough() throws Exception {
        mvc.perform(get("/audit/records").param("transferId", TRANSFER_ID)
                        .header("X-User-Roles", "NOT_AUDITOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aQueryWithoutTransferIdUserIdOrFromIsAValidationError() throws Exception {
        mvc.perform(get("/audit/records").header("X-User-Roles", "AUDITOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("transferId"));
    }

    @Test
    void badParametersAreValidationErrorsWithTheirField() throws Exception {
        expectValidationError("transferId", "not-a-uuid", "transferId");
        expectValidationError("from", "yesterday", "from");
        expectValidationError("size", "500", "size");
        expectValidationError("size", "ten", "size");
    }

    @Test
    void anAuditorVerifiesTheChain() throws Exception {
        when(records.findLastLink()).thenReturn(Optional.empty());

        mvc.perform(get("/audit/verify").header("X-User-Roles", "AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checkedRecords").value(0))
                .andExpect(jsonPath("$.verifiedAt").value(NOW.toString()));
    }

    @Test
    void verifyRejectsABadRange() throws Exception {
        mvc.perform(get("/audit/verify").param("fromSeq", "abc").header("X-User-Roles", "AUDITOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("fromSeq"));
        mvc.perform(get("/audit/verify").param("fromSeq", "9").param("toSeq", "3")
                        .header("X-User-Roles", "AUDITOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("toSeq"));

        verify(records, never()).findRange(anyLong(), anyLong(), anyInt());
    }

    @Test
    void anUnexpectedErrorIsAnInternalErrorWithoutDetails() throws Exception {
        when(records.find(any(AuditQuery.class))).thenThrow(new IllegalStateException("connection refused"));

        mvc.perform(get("/audit/records").param("transferId", TRANSFER_ID).header("X-User-Roles", "AUDITOR"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error."));
    }

    private void expectValidationError(String param, String value, String field) throws Exception {
        mvc.perform(get("/audit/records").param("userId", UUID.randomUUID().toString()).param(param, value)
                        .header("X-User-Roles", "AUDITOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value(field));
    }

    private static AttemptRecorded attempt() {
        return new AttemptRecorded(UUID.fromString("3f9a6c1e-7b2d-4c5e-8f9a-0b1c2d3e4f5a"), AttemptSource.TRANSFER,
                AttemptStage.REQUEST, AttemptOutcome.ACCEPTED, null, UUID.fromString(TRANSFER_ID),
                UUID.fromString("f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b"), "7e57d004-2b97-4e7a-b45f-5387367791cd",
                "150000.00", 202, UUID.fromString("5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11"),
                Instant.parse("2026-10-19T15:00:00Z"));
    }
}
