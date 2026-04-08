package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.erc3643.Erc3643ClaimTopic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Erc3643ClaimTopic} entities.
 */
public interface Erc3643ClaimTopicRepository extends JpaRepository<Erc3643ClaimTopic, UUID> {

    /** Returns all required claim topics registered for a given suite. */
    List<Erc3643ClaimTopic> findBySuiteId(UUID suiteId);
}
