package co.com.finova.audit.api;

import co.com.finova.audit.api.dto.AuditPageResponse;
import co.com.finova.audit.api.dto.ChainVerificationResponse;
import co.com.finova.audit.api.error.ForbiddenRoleException;
import co.com.finova.audit.model.query.AuditQuery;
import co.com.finova.audit.usecase.queryrecords.QueryAuditRecordsUseCase;
import co.com.finova.audit.usecase.verifychain.VerifyAuditChainUseCase;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * GET /audit/records and GET /audit/verify (spec §3.10, §3.11). Both are read-only and require the role
 * AUDITOR, checked by the Gateway and again here from X-User-Roles (comma separated), which the Gateway
 * strips from the client request and injects (§1.5, F5.7).
 */
@RestController
public class AuditController {

    public static final String ROLES_HEADER = "X-User-Roles";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    static final String AUDITOR_ROLE = "AUDITOR";

    private final QueryAuditRecordsUseCase queryRecords;
    private final VerifyAuditChainUseCase verifyChain;

    public AuditController(QueryAuditRecordsUseCase queryRecords, VerifyAuditChainUseCase verifyChain) {
        this.queryRecords = queryRecords;
        this.verifyChain = verifyChain;
    }

    @GetMapping(path = "/audit/records", produces = MediaType.APPLICATION_JSON_VALUE)
    public AuditPageResponse records(@RequestHeader(name = ROLES_HEADER, required = false) String roles,
                                     @RequestParam(name = "transferId", required = false) String transferId,
                                     @RequestParam(name = "userId", required = false) String userId,
                                     @RequestParam(name = "from", required = false) String from,
                                     @RequestParam(name = "to", required = false) String to,
                                     @RequestParam(name = "page", required = false) String page,
                                     @RequestParam(name = "size", required = false) String size) {
        requireAuditor(roles);
        AuditQuery query = AuditQuery.of(
                QueryParams.uuid("transferId", transferId),
                QueryParams.uuid("userId", userId),
                QueryParams.dateTime("from", from),
                QueryParams.dateTime("to", to),
                QueryParams.integer("page", page),
                QueryParams.integer("size", size));
        return AuditPageResponse.from(queryRecords.handle(query));
    }

    @GetMapping(path = "/audit/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChainVerificationResponse verify(@RequestHeader(name = ROLES_HEADER, required = false) String roles,
                                            @RequestParam(name = "fromSeq", required = false) String fromSeq,
                                            @RequestParam(name = "toSeq", required = false) String toSeq) {
        requireAuditor(roles);
        return ChainVerificationResponse.from(verifyChain.handle(
                QueryParams.longInteger("fromSeq", fromSeq),
                QueryParams.longInteger("toSeq", toSeq)));
    }

    private static void requireAuditor(String roles) {
        boolean auditor = roles != null && Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(AUDITOR_ROLE::equals);
        if (!auditor) {
            throw new ForbiddenRoleException(roles);
        }
    }
}
