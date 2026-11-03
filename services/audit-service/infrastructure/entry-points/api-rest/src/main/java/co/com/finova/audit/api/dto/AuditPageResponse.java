package co.com.finova.audit.api.dto;

import co.com.finova.audit.model.query.AuditPage;

import java.util.List;

/** Page of AuditRecord (spec §3.10): page, size, total, items. */
public record AuditPageResponse(int page, int size, long total, List<AuditRecordResponse> items) {

    public static AuditPageResponse from(AuditPage page) {
        return new AuditPageResponse(page.page(), page.size(), page.total(),
                page.items().stream().map(AuditRecordResponse::from).toList());
    }
}
