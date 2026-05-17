package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.customer.CustomerApi;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class CustomerApiImpl implements CustomerApi {

    private final LegalEntityRepository repository;
    private final EntityNumberGenerator numberGenerator;

    CustomerApiImpl(LegalEntityRepository repository, EntityNumberGenerator numberGenerator) {
        this.repository = repository;
        this.numberGenerator = numberGenerator;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LegalEntity> findLegalEntity(UUID id) {
        return repository.findById(id);
    }

    @Override
    public void activateLegalEntity(UUID id) {
        repository.findById(id).ifPresent(e -> {
            e.setStatus(EntityStatus.ACTIVE);
            repository.save(e);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public String nextEntityNumber() {
        return numberGenerator.generateEntityNumber();
    }
}
