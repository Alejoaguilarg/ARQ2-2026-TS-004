package co.com.finova.wallet.model.common.gateways;

import java.util.UUID;

/** Source of new UUID v4 identifiers (spec §1.5) for messages, ledger entries and audit events. */
public interface IdGenerator {

    UUID newId();
}
