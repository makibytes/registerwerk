package de.makibytes.registerwerk.unit;

import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.externalref.api.CompanyExternalReferenceService;
import de.makibytes.registerwerk.customer.api.CompanyExternalReference;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.customer.api.CompanyExternalReferenceRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.customer.web.dto.CompanyExternalReferenceLookupResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyExternalReferenceService unit tests")
class CompanyExternalReferenceServiceTest {

    @Mock private CompanyExternalReferenceRepository referenceRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private AssetLookupPort assetLookupPort;
    @Mock private AssetHolderRepository assetHolderRepository;
    @Mock private Erc3643IdentityRegistryRepository identityRegistryRepository;
    @Mock private Erc3643SuiteRepository suiteRepository;
    @Mock private AssetDeploymentRepository assetDeploymentRepository;
    @Mock private OnchainIdentityRepository onchainIdentityRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private CompanyExternalReferenceService service;

    @BeforeEach
    void setUp() {
        service = new CompanyExternalReferenceService(
                referenceRepository,
                legalEntityRepository,
                assetLookupPort,
                assetHolderRepository,
                identityRegistryRepository,
                suiteRepository,
                assetDeploymentRepository,
                onchainIdentityRepository,
                eventPublisher
        );
    }

    @Test
    @DisplayName("upsert stores company-scoped asset external IDs without global uniqueness")
    void upsert_storesCompanyScopedAssetExternalIdsWithoutGlobalUniqueness() {
        UUID assetId = UUID.randomUUID();
        UUID issuerCompanyId = UUID.randomUUID();
        UUID investorCompanyId = UUID.randomUUID();

        AssetLookupPort.AssetInfo asset = buildAsset(assetId, issuerCompanyId, "Green Bond 2028");
        when(assetLookupPort.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetHolderRepository.existsByAssetIdAndInvestorId(assetId, investorCompanyId)).thenReturn(true);
        when(referenceRepository.findByOwnerLegalEntityIdAndSubjectTypeAndSubjectId(issuerCompanyId, ExternalReferenceSubjectType.ASSET, assetId))
                .thenReturn(Optional.empty());
        when(referenceRepository.findByOwnerLegalEntityIdAndSubjectTypeAndSubjectId(investorCompanyId, ExternalReferenceSubjectType.ASSET, assetId))
                .thenReturn(Optional.empty());
        when(referenceRepository.save(any(CompanyExternalReference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.upsert(authentication(issuerCompanyId), ExternalReferenceSubjectType.ASSET, assetId, "ERP-123");
        service.upsert(authentication(investorCompanyId), ExternalReferenceSubjectType.ASSET, assetId, "ERP-123");

        ArgumentCaptor<CompanyExternalReference> captor = ArgumentCaptor.forClass(CompanyExternalReference.class);
        verify(referenceRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<CompanyExternalReference> saved = captor.getAllValues();
        assertThat(saved)
                .extracting(CompanyExternalReference::getOwnerLegalEntityId)
                .containsExactly(issuerCompanyId, investorCompanyId);
        assertThat(saved)
                .extracting(CompanyExternalReference::getExternalId)
                .containsExactly("ERP-123", "ERP-123");
    }

    @Test
    @DisplayName("lookup returns only the authenticated company's matching references")
    void lookup_returnsOnlyTheAuthenticatedCompanysMatchingReferences() {
        UUID ownerCompanyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2025-01-01T00:00:00Z");

        CompanyExternalReference ownerReference = buildReference(ownerCompanyId, entityId, "INV-42", updatedAt);
        when(referenceRepository.findByOwnerLegalEntityIdAndExternalIdOrderByUpdatedAtDesc(ownerCompanyId, "INV-42"))
                .thenReturn(List.of(ownerReference));
        when(referenceRepository.findByOwnerLegalEntityIdAndExternalIdOrderByUpdatedAtDesc(otherCompanyId, "INV-42"))
                .thenReturn(List.of());
        when(legalEntityRepository.findAllById(any(Iterable.class))).thenReturn(List.of(buildEntity(entityId, "Investor Bar", "ENT-42")));
        when(assetLookupPort.findAll()).thenReturn(List.of());
        when(assetHolderRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
        when(identityRegistryRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
        when(suiteRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
        when(assetDeploymentRepository.findAllById(any(Iterable.class))).thenReturn(List.of());
        when(onchainIdentityRepository.findAllById(any(Iterable.class))).thenReturn(List.of());

        List<CompanyExternalReferenceLookupResponse> ownerResults =
                service.lookup(authentication(ownerCompanyId), "INV-42", null);
        List<CompanyExternalReferenceLookupResponse> otherResults =
                service.lookup(authentication(otherCompanyId), "INV-42", null);

        assertThat(ownerResults).singleElement().satisfies(result -> {
            assertThat(result.subjectType()).isEqualTo(ExternalReferenceSubjectType.LEGAL_ENTITY);
            assertThat(result.subjectId()).isEqualTo(entityId);
            assertThat(result.externalId()).isEqualTo("INV-42");
            assertThat(result.displayName()).isEqualTo("Investor Bar");
            assertThat(result.contextLabel()).isEqualTo("ENT-42");
            assertThat(result.updatedAt()).isEqualTo(updatedAt);
        });
        assertThat(otherResults).isEmpty();
    }

    @Test
    @DisplayName("findExternalIds resolves values only for the authenticated company")
    void findExternalIds_resolvesValuesOnlyForTheAuthenticatedCompany() {
        UUID ownerCompanyId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();

        CompanyExternalReference reference = buildReference(ownerCompanyId, subjectId, "CUS-100", Instant.now());
        when(referenceRepository.findByOwnerLegalEntityIdAndSubjectTypeAndSubjectIdIn(
                ownerCompanyId,
                ExternalReferenceSubjectType.LEGAL_ENTITY,
                List.of(subjectId)
        )).thenReturn(List.of(reference));

        Map<UUID, String> resolved = service.findExternalIds(
                authentication(ownerCompanyId),
                ExternalReferenceSubjectType.LEGAL_ENTITY,
                List.of(subjectId)
        );

        assertThat(resolved).containsEntry(subjectId, "CUS-100");
        verify(referenceRepository).findByOwnerLegalEntityIdAndSubjectTypeAndSubjectIdIn(
                ownerCompanyId,
                ExternalReferenceSubjectType.LEGAL_ENTITY,
                List.of(subjectId)
        );
    }

    private Authentication authentication(UUID entityId) {
        UUID actorId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(actorId.toString())
                .claim("entity_id", entityId.toString())
                .claim("roles", List.of("ISSUER"))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private AssetLookupPort.AssetInfo buildAsset(UUID assetId, UUID issuerId, String name) {
        return new AssetLookupPort.AssetInfo(assetId, name, null, TokenStandard.ERC20, null, null, issuerId, "AST-1", "ISSUED");
    }

    private LegalEntity buildEntity(UUID entityId, String name, String entityNumber) {
        LegalEntity entity = new LegalEntity();
        entity.setId(entityId);
        entity.setType(EntityType.INVESTOR);
        entity.setStatus(EntityStatus.ACTIVE);
        entity.setCurrentName(name);
        entity.setEntityNumber(entityNumber);
        return entity;
    }

    private CompanyExternalReference buildReference(UUID ownerId, UUID subjectId, String externalId, Instant updatedAt) {
        CompanyExternalReference reference = new CompanyExternalReference();
        reference.setOwnerLegalEntityId(ownerId);
        reference.setSubjectType(ExternalReferenceSubjectType.LEGAL_ENTITY);
        reference.setSubjectId(subjectId);
        reference.setExternalId(externalId);
        reference.setUpdatedBy(UUID.randomUUID());
        return setUpdatedAt(reference, updatedAt);
    }

    private CompanyExternalReference setUpdatedAt(CompanyExternalReference reference, Instant updatedAt) {
        try {
            var field = CompanyExternalReference.class.getDeclaredField("updatedAt");
            field.setAccessible(true);
            field.set(reference, updatedAt);
            return reference;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
