package co.com.finova.identity.jpa;

import co.com.finova.identity.model.session.gateways.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

// One upsert, so no entity: registered_at keeps the first login, last_seen_at moves forward.
@Component
@RequiredArgsConstructor
public class DeviceRepositoryAdapter implements DeviceRepository {

    private final JdbcTemplate jdbc;

    @Override
    public void recordLogin(UUID userId, String deviceId) {
        jdbc.update("""
                INSERT INTO devices (user_id, device_id) VALUES (?, ?)
                ON CONFLICT (user_id, device_id) DO UPDATE SET last_seen_at = now()
                """, userId, deviceId);
    }
}
