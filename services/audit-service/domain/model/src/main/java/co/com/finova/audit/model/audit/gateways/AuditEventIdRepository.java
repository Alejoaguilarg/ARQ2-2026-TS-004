package co.com.finova.audit.model.audit.gateways;

import co.com.finova.audit.model.audit.AuditEventId;

import java.util.UUID;

public interface AuditEventIdRepository {

    boolean exists(UUID eventId);

    void save(AuditEventId auditEventId);
}