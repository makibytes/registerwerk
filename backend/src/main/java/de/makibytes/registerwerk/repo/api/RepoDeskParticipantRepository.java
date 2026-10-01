package de.makibytes.registerwerk.repo.api;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RepoDeskParticipantRepository extends JpaRepository<RepoDeskParticipant, UUID> {
    List<RepoDeskParticipant> findByListedTrueAndOptedOutAtIsNull();
}
