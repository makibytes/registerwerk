package de.makibytes.registerwerk.kyc.api;

import de.makibytes.registerwerk.kyc.api.KycDocumentContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KycDocumentContentRepository extends JpaRepository<KycDocumentContent, UUID> {
}
