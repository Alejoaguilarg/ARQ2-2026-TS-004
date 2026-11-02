package co.com.finova.audit.model.audit.gateways;

import co.com.finova.audit.model.audit.AuditRecord;

public interface AuditHashGenerator {

    String generate(AuditRecord auditRecord);
}