package co.com.finova.transfer.model.common.gateways;

import java.util.UUID;

/** Source of new UUID v4 identifiers (spec §1.5) for transfers, messages and audit events. */
public interface IdGenerator {

    UUID newId();
}
