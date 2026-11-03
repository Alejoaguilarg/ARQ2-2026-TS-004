package co.com.finova.audit.model.query;

import co.com.finova.audit.model.record.AuditRecord;

import java.util.List;

/** One page of audit records ordered by seq; total counts every record that matches the query. */
public record AuditPage(int page, int size, long total, List<AuditRecord> items) {

    public AuditPage {
        items = List.copyOf(items);
    }
}
