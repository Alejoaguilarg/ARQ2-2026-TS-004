package co.com.finova.transfer.api.transfer;

import co.com.finova.transfer.api.config.CorrelationIdFilter;
import co.com.finova.transfer.api.config.UuidFormat;
import co.com.finova.transfer.api.error.InvalidIdentityHeaderException;
import co.com.finova.transfer.api.error.InvalidRequestException;
import co.com.finova.transfer.api.error.MissingIdempotencyKeyException;
import co.com.finova.transfer.model.transfer.TransferNotFoundException;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.usecase.createtransfer.CreateTransferUseCase;
import co.com.finova.transfer.usecase.gettransfer.GetTransferUseCase;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * POST /transfers (spec §3.8) and GET /transfers/{transferId} (§3.9). The caller comes only from X-User-Id, which the
 * Gateway strips from the client request and injects (§1.5); a missing or invalid one is a Gateway fault (500).
 */
@RestController
public class TransferController {

    static final String USER_ID_HEADER = "X-User-Id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final CreateTransferUseCase createTransfer;
    private final GetTransferUseCase getTransfer;

    public TransferController(CreateTransferUseCase createTransfer, GetTransferUseCase getTransfer) {
        this.createTransfer = createTransfer;
        this.getTransfer = getTransfer;
    }

    @PostMapping(path = "/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransferResponses.Accepted> create(
            @RequestHeader(name = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) byte[] body) {
        UUID sender = parseUserId(userId);
        UUID correlationId = correlationId();
        TransferRequest request;
        try {
            if (!UuidFormat.isUuid(idempotencyKey)) {
                throw new MissingIdempotencyKeyException();
            }
            request = TransferBody.parse(body == null ? new byte[0] : body, sender, UUID.fromString(idempotencyKey));
        } catch (InvalidRequestException | MissingIdempotencyKeyException e) {
            createTransfer.rejectInvalid(sender, idempotencyKey, correlationId);
            throw e;
        }
        CreateTransferUseCase.Result result = createTransfer.handle(request, correlationId);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, TransferResponses.url(result.transfer()));
        headers.add(CorrelationIdFilter.HEADER, correlationId.toString());
        if (result.replayed()) {
            headers.add(REPLAYED_HEADER, "true");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).headers(headers)
                .body(TransferResponses.Accepted.of(result.transfer()));
    }

    @GetMapping(path = "/transfers/{transferId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TransferResponses.View get(@RequestHeader(name = USER_ID_HEADER, required = false) String userId,
                                      @PathVariable("transferId") String transferId) {
        UUID caller = parseUserId(userId);
        if (!UuidFormat.isUuid(transferId)) {
            throw new TransferNotFoundException(null);
        }
        return TransferResponses.View.of(getTransfer.handle(UUID.fromString(transferId), caller));
    }

    private static UUID parseUserId(String header) {
        if (!UuidFormat.isUuid(header)) {
            throw new InvalidIdentityHeaderException(USER_ID_HEADER, header);
        }
        return UUID.fromString(header);
    }

    // The Gateway's id when it sent one (CorrelationIdFilter put it in the MDC); a new one for a direct call.
    private static UUID correlationId() {
        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
        return id == null ? UUID.randomUUID() : UUID.fromString(id);
    }
}
