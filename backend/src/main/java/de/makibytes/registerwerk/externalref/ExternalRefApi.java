package de.makibytes.registerwerk.externalref;

import de.makibytes.registerwerk.customer.api.CompanyExternalReference;
import de.makibytes.registerwerk.customer.api.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.customer.web.dto.CompanyExternalReferenceLookupResponse;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Public API for managing external-reference identifiers tied to registry objects. */
public interface ExternalRefApi {

    CompanyExternalReference upsert(Authentication authentication, ExternalReferenceSubjectType subjectType, UUID subjectId, String externalId);

    void delete(Authentication authentication, ExternalReferenceSubjectType subjectType, UUID subjectId);

    Optional<String> findExternalId(Authentication authentication, ExternalReferenceSubjectType subjectType, UUID subjectId);

    Map<UUID, String> findExternalIds(Authentication authentication, ExternalReferenceSubjectType subjectType, List<UUID> subjectIds);

    List<CompanyExternalReferenceLookupResponse> list(Authentication authentication);

    List<CompanyExternalReferenceLookupResponse> lookup(Authentication authentication, ExternalReferenceSubjectType subjectType, List<UUID> subjectIds);
}
