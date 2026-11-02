package co.com.finova.audit.model.audit;

import java.util.List;

public record AuditPage(
        int page,
        int size,
        long total,
        List<AuditRecord> items
) {

    public AuditPage {
        items = List.copyOf(items);
    }
}