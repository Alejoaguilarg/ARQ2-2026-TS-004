package co.com.finova.notification.jpa;

import co.com.finova.notification.jpa.entity.RecipientContactEntity;
import co.com.finova.notification.model.notification.RecipientContact;
import co.com.finova.notification.model.notification.gateways.RecipientContactRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven adapter: implements the {@link RecipientContactRepository} port with Spring Data JPA.
 */
@Repository
public class RecipientContactRepositoryAdapter implements RecipientContactRepository {

    private final RecipientContactJpaRepository repository;

    public RecipientContactRepositoryAdapter(RecipientContactJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<RecipientContact> findByUserId(UUID userId) {
        return repository.findById(userId).map(RecipientContactRepositoryAdapter::toDomain);
    }

    static RecipientContact toDomain(RecipientContactEntity entity) {
        return new RecipientContact(entity.getUserId(), entity.getEmail(), entity.getPhone(),
                entity.getPushToken(), entity.getPreferredChannel());
    }
}
