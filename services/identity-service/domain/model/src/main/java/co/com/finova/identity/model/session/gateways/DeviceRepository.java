package co.com.finova.identity.model.session.gateways;

import java.util.UUID;

public interface DeviceRepository {

    // Registers the device on its first login and updates lastSeenAt on later ones.
    void recordLogin(UUID userId, String deviceId);
}
