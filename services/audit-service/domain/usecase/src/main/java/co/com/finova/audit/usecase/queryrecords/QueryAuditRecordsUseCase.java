package co.com.finova.audit.usecase.queryrecords;

import co.com.finova.audit.model.common.Contract;
import co.com.finova.audit.model.query.AuditPage;
import co.com.finova.audit.model.query.AuditQuery;
import co.com.finova.audit.model.record.gateways.AuditRecordRepository;

/**
 * GET /audit/records (spec §3.10, F5.6): read-only query of the trail, ordered by seq ascending. Every
 * record of a transfer is returned, including the Gateway rejections and duplicates.
 */
public class QueryAuditRecordsUseCase {

    private final AuditRecordRepository records;

    public QueryAuditRecordsUseCase(AuditRecordRepository records) {
        this.records = records;
    }

    public AuditPage handle(AuditQuery query) {
        Contract.requireNonNull(query, "query");
        return records.find(query);
    }
}
