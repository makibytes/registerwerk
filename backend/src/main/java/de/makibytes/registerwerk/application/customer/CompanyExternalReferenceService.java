package de.makibytes.registerwerk.application.customer;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.asset.AssetHolder;
import de.makibytes.registerwerk.domain.customer.CompanyExternalReference;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.enums.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.domain.erc3643.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.domain.erc3643.Erc3643Suite;
import de.makibytes.registerwerk.domain.erc3643.OnchainIdentity;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetDeploymentRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetHolderRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.CompanyExternalReferenceRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.Erc3643SuiteRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.OnchainIdentityRepository;
import de.makibytes.registerwerk.web.dto.CompanyExternalReferenceLookupResponse;
import de.makibytes.registerwerk.web.security.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class CompanyExternalReferenceService {

    private final CompanyExternalReferenceRepository referenceRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final AssetRepository assetRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final Erc3643IdentityRegistryRepository identityRegistryRepository;
    private final Erc3643SuiteRepository suiteRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final OnchainIdentityRepository onchainIdentityRepository;
    private final AuditEventPublisher auditEventPublisher;

    public CompanyExternalReferenceService(
            CompanyExternalReferenceRepository referenceRepository,
            LegalEntityRepository legalEntityRepository,
            AssetRepository assetRepository,
            AssetHolderRepository assetHolderRepository,
            Erc3643IdentityRegistryRepository identityRegistryRepository,
            Erc3643SuiteRepository suiteRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            OnchainIdentityRepository onchainIdentityRepository,
            AuditEventPublisher auditEventPublisher) {
        this.referenceRepository = referenceRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.assetRepository = assetRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.identityRegistryRepository = identityRegistryRepository;
        this.suiteRepository = suiteRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.onchainIdentityRepository = onchainIdentityRepository;
        this.auditEventPublisher = auditEventPublisher;
    }

    public CompanyExternalReference upsert(
            Authentication authentication,
            ExternalReferenceSubjectType subjectType,
            UUID subjectId,
            String externalId) {
        UUID ownerEntityId = requireOwnerEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureSubjectAccessible(ownerEntityId, subjectType, subjectId);

        CompanyExternalReference reference = referenceRepository
                .findByOwnerLegalEntityIdAndSubjectTypeAndSubjectId(ownerEntityId, subjectType, subjectId)
                .orElseGet(CompanyExternalReference::new);

        reference.setOwnerLegalEntityId(ownerEntityId);
        reference.setSubjectType(subjectType);
        reference.setSubjectId(subjectId);
        reference.setExternalId(externalId.trim());
        reference.setUpdatedBy(actorId);
        CompanyExternalReference saved = referenceRepository.save(reference);

        auditEventPublisher.publish(
                "COMPANY_EXTERNAL_ID_UPSERTED",
                subjectType.auditSubjectType(),
                subjectId,
                actorId,
                null,
                Map.of(
                        "ownerEntityId", ownerEntityId.toString(),
                        "subjectType", subjectType.name(),
                        "externalId", saved.getExternalId()
                )
        );
        return saved;
    }

    public void delete(Authentication authentication, ExternalReferenceSubjectType subjectType, UUID subjectId) {
        UUID ownerEntityId = requireOwnerEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        referenceRepository.findByOwnerLegalEntityIdAndSubjectTypeAndSubjectId(ownerEntityId, subjectType, subjectId)
                .ifPresent(reference -> {
                    referenceRepository.delete(reference);
                    auditEventPublisher.publish(
                            "COMPANY_EXTERNAL_ID_REMOVED",
                            subjectType.auditSubjectType(),
                            subjectId,
                            actorId,
                            null,
                            Map.of(
                                    "ownerEntityId", ownerEntityId.toString(),
                                    "subjectType", subjectType.name(),
                                    "externalId", reference.getExternalId()
                            )
                    );
                });
    }

    @Transactional(readOnly = true)
    public Optional<String> findExternalId(
            Authentication authentication,
            ExternalReferenceSubjectType subjectType,
            UUID subjectId) {
        UUID ownerEntityId = SecurityUtils.extractEntityId(authentication);
        if (ownerEntityId == null) {
            return Optional.empty();
        }
        return referenceRepository
                .findByOwnerLegalEntityIdAndSubjectTypeAndSubjectId(ownerEntityId, subjectType, subjectId)
                .map(CompanyExternalReference::getExternalId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> findExternalIds(
            Authentication authentication,
            ExternalReferenceSubjectType subjectType,
            Collection<UUID> subjectIds) {
        UUID ownerEntityId = SecurityUtils.extractEntityId(authentication);
        if (ownerEntityId == null || subjectIds == null || subjectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> resolved = new LinkedHashMap<>();
        referenceRepository.findByOwnerLegalEntityIdAndSubjectTypeAndSubjectIdIn(ownerEntityId, subjectType, subjectIds)
                .forEach(reference -> resolved.put(reference.getSubjectId(), reference.getExternalId()));
        return resolved;
    }

    @Transactional(readOnly = true)
    public List<CompanyExternalReferenceLookupResponse> list(
            Authentication authentication,
            ExternalReferenceSubjectType subjectType) {
        UUID ownerEntityId = requireOwnerEntityId(authentication);
        List<CompanyExternalReference> references = subjectType == null
                ? referenceRepository.findByOwnerLegalEntityIdOrderByUpdatedAtDesc(ownerEntityId)
                : referenceRepository.findByOwnerLegalEntityIdAndSubjectTypeOrderByUpdatedAtDesc(ownerEntityId, subjectType);
        return buildLookupResponses(references);
    }

    @Transactional(readOnly = true)
    public List<CompanyExternalReferenceLookupResponse> lookup(
            Authentication authentication,
            String externalId,
            ExternalReferenceSubjectType subjectType) {
        UUID ownerEntityId = requireOwnerEntityId(authentication);
        List<CompanyExternalReference> references = subjectType == null
                ? referenceRepository.findByOwnerLegalEntityIdAndExternalIdOrderByUpdatedAtDesc(ownerEntityId, externalId.trim())
                : referenceRepository.findByOwnerLegalEntityIdAndSubjectTypeAndExternalIdOrderByUpdatedAtDesc(
                        ownerEntityId,
                        subjectType,
                        externalId.trim());
        return buildLookupResponses(references);
    }

    private UUID requireOwnerEntityId(Authentication authentication) {
        UUID ownerEntityId = SecurityUtils.extractEntityId(authentication);
        if (ownerEntityId == null) {
            throw new IllegalArgumentException("Customer external IDs require an authenticated company context");
        }
        return ownerEntityId;
    }

    private void ensureSubjectAccessible(UUID ownerEntityId, ExternalReferenceSubjectType subjectType, UUID subjectId) {
        switch (subjectType) {
            case LEGAL_ENTITY -> ensureLegalEntityAccessible(ownerEntityId, subjectId);
            case ASSET -> ensureAssetAccessible(ownerEntityId, subjectId);
            case ASSET_HOLDER -> ensureAssetHolderAccessible(ownerEntityId, subjectId);
            case ERC3643_IDENTITY_REGISTRY_ENTRY -> ensureIdentityRegistryEntryAccessible(ownerEntityId, subjectId);
        }
    }

    private void ensureLegalEntityAccessible(UUID ownerEntityId, UUID subjectId) {
        LegalEntity entity = legalEntityRepository.findById(subjectId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", subjectId));
        if (ownerEntityId.equals(entity.getId())) {
            return;
        }
        List<UUID> ownerAssetIds = assetRepository.findIdsByIssuerId(ownerEntityId);
        if (!ownerAssetIds.isEmpty() && assetHolderRepository.existsByInvestorIdAndAssetIdIn(subjectId, ownerAssetIds)) {
            return;
        }
        if (assetHolderRepository.existsByInvestorIdAndIssuerId(ownerEntityId, subjectId)) {
            return;
        }
        throw new IllegalArgumentException("The authenticated company cannot assign an external ID to this legal entity");
    }

    private void ensureAssetAccessible(UUID ownerEntityId, UUID subjectId) {
        Asset asset = assetRepository.findById(subjectId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", subjectId));
        if (ownerEntityId.equals(asset.getIssuerId())
                || assetHolderRepository.existsByAssetIdAndInvestorId(subjectId, ownerEntityId)) {
            return;
        }
        throw new IllegalArgumentException("The authenticated company cannot assign an external ID to this asset");
    }

    private void ensureAssetHolderAccessible(UUID ownerEntityId, UUID subjectId) {
        AssetHolder holder = assetHolderRepository.findById(subjectId)
                .orElseThrow(() -> new EntityNotFoundException("AssetHolder", subjectId));
        if (ownerEntityId.equals(holder.getInvestorId())) {
            return;
        }
        Asset asset = assetRepository.findById(holder.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", holder.getAssetId()));
        if (ownerEntityId.equals(asset.getIssuerId())) {
            return;
        }
        throw new IllegalArgumentException("The authenticated company cannot assign an external ID to this holding");
    }

    private void ensureIdentityRegistryEntryAccessible(UUID ownerEntityId, UUID subjectId) {
        Erc3643IdentityRegistry entry = identityRegistryRepository.findById(subjectId)
                .orElseThrow(() -> new EntityNotFoundException("Erc3643IdentityRegistry", subjectId));
        Asset asset = resolveAssetForIdentityEntry(entry);
        if (asset != null && ownerEntityId.equals(asset.getIssuerId())) {
            return;
        }
        Optional<OnchainIdentity> identity = onchainIdentityRepository.findById(entry.getOnchainIdentityId());
        if (identity.isPresent() && ownerEntityId.equals(identity.get().getLegalEntityId())) {
            return;
        }
        throw new IllegalArgumentException("The authenticated company cannot assign an external ID to this identity registry entry");
    }

    private List<CompanyExternalReferenceLookupResponse> buildLookupResponses(List<CompanyExternalReference> references) {
        if (references.isEmpty()) {
            return List.of();
        }

        Map<UUID, LegalEntity> legalEntities = legalEntityRepository.findAllById(subjectIds(references, ExternalReferenceSubjectType.LEGAL_ENTITY))
                .stream()
                .collect(LinkedHashMap::new, (map, entity) -> map.put(entity.getId(), entity), Map::putAll);

        Map<UUID, Asset> assets = assetRepository.findAllById(subjectIds(references, ExternalReferenceSubjectType.ASSET))
                .stream()
                .collect(LinkedHashMap::new, (map, asset) -> map.put(asset.getId(), asset), Map::putAll);

        Map<UUID, AssetHolder> holders = assetHolderRepository.findAllById(subjectIds(references, ExternalReferenceSubjectType.ASSET_HOLDER))
                .stream()
                .collect(LinkedHashMap::new, (map, holder) -> map.put(holder.getId(), holder), Map::putAll);

        List<Erc3643IdentityRegistry> registryEntries = identityRegistryRepository
                .findAllById(subjectIds(references, ExternalReferenceSubjectType.ERC3643_IDENTITY_REGISTRY_ENTRY));
        Map<UUID, Erc3643IdentityRegistry> identityEntries = new LinkedHashMap<>();
        registryEntries.forEach(entry -> identityEntries.put(entry.getId(), entry));

        Map<UUID, Asset> holderAssets = assetRepository.findAllById(
                        holders.values().stream().map(AssetHolder::getAssetId).distinct().toList())
                .stream()
                .collect(LinkedHashMap::new, (map, asset) -> map.put(asset.getId(), asset), Map::putAll);

        Map<UUID, Erc3643Suite> suites = suiteRepository.findAllById(
                        registryEntries.stream().map(Erc3643IdentityRegistry::getSuiteId).distinct().toList())
                .stream()
                .collect(LinkedHashMap::new, (map, suite) -> map.put(suite.getId(), suite), Map::putAll);

        Map<UUID, AssetDeployment> deployments = assetDeploymentRepository.findAllById(
                        suites.values().stream().map(Erc3643Suite::getAssetDeploymentId).distinct().toList())
                .stream()
                .collect(LinkedHashMap::new, (map, deployment) -> map.put(deployment.getId(), deployment), Map::putAll);

        Map<UUID, Asset> registryAssets = assetRepository.findAllById(
                        deployments.values().stream().map(AssetDeployment::getAssetId).distinct().toList())
                .stream()
                .collect(LinkedHashMap::new, (map, asset) -> map.put(asset.getId(), asset), Map::putAll);

        Map<UUID, OnchainIdentity> onchainIdentities = onchainIdentityRepository.findAllById(
                        registryEntries.stream().map(Erc3643IdentityRegistry::getOnchainIdentityId).distinct().toList())
                .stream()
                .collect(LinkedHashMap::new, (map, identity) -> map.put(identity.getId(), identity), Map::putAll);

        Map<UUID, LegalEntity> registryLegalEntities = legalEntityRepository.findAllById(
                        onchainIdentities.values().stream().map(OnchainIdentity::getLegalEntityId).distinct().toList())
                .stream()
                .collect(LinkedHashMap::new, (map, entity) -> map.put(entity.getId(), entity), Map::putAll);

        List<CompanyExternalReferenceLookupResponse> responses = new ArrayList<>();
        for (CompanyExternalReference reference : references) {
            responses.add(toLookupResponse(
                    reference,
                    legalEntities,
                    assets,
                    holders,
                    holderAssets,
                    identityEntries,
                    suites,
                    deployments,
                    registryAssets,
                    onchainIdentities,
                    registryLegalEntities
            ));
        }
        return responses;
    }

    private CompanyExternalReferenceLookupResponse toLookupResponse(
            CompanyExternalReference reference,
            Map<UUID, LegalEntity> legalEntities,
            Map<UUID, Asset> assets,
            Map<UUID, AssetHolder> holders,
            Map<UUID, Asset> holderAssets,
            Map<UUID, Erc3643IdentityRegistry> identityEntries,
            Map<UUID, Erc3643Suite> suites,
            Map<UUID, AssetDeployment> deployments,
            Map<UUID, Asset> registryAssets,
            Map<UUID, OnchainIdentity> onchainIdentities,
            Map<UUID, LegalEntity> registryLegalEntities) {
        return switch (reference.getSubjectType()) {
            case LEGAL_ENTITY -> {
                LegalEntity entity = legalEntities.get(reference.getSubjectId());
                String displayName = entity != null ? entity.getCurrentName() : "Unknown legal entity";
                String context = entity != null
                        ? firstNonBlank(entity.getEntityNumber(), entity.getRegistrationNumber())
                        : null;
                yield new CompanyExternalReferenceLookupResponse(
                        reference.getSubjectType(),
                        reference.getSubjectId(),
                        reference.getExternalId(),
                        displayName,
                        context,
                        null,
                        reference.getUpdatedAt());
            }
            case ASSET -> {
                Asset asset = assets.get(reference.getSubjectId());
                String displayName = asset != null ? asset.getName() : "Unknown asset";
                String context = asset != null ? firstNonBlank(asset.getAssetNumber(), asset.getIsin()) : null;
                yield new CompanyExternalReferenceLookupResponse(
                        reference.getSubjectType(),
                        reference.getSubjectId(),
                        reference.getExternalId(),
                        displayName,
                        context,
                        asset != null ? asset.getId() : null,
                        reference.getUpdatedAt());
            }
            case ASSET_HOLDER -> {
                AssetHolder holder = holders.get(reference.getSubjectId());
                Asset asset = holder != null ? holderAssets.get(holder.getAssetId()) : null;
                String displayName = asset != null ? asset.getName() : "Asset holding";
                String context = holder != null
                        ? holder.getWalletAddress()
                        : null;
                yield new CompanyExternalReferenceLookupResponse(
                        reference.getSubjectType(),
                        reference.getSubjectId(),
                        reference.getExternalId(),
                        displayName,
                        context,
                        holder != null ? holder.getAssetId() : null,
                        reference.getUpdatedAt());
            }
            case ERC3643_IDENTITY_REGISTRY_ENTRY -> {
                Erc3643IdentityRegistry entry = identityEntries.get(reference.getSubjectId());
                OnchainIdentity identity = entry != null ? onchainIdentities.get(entry.getOnchainIdentityId()) : null;
                LegalEntity entity = identity != null ? registryLegalEntities.get(identity.getLegalEntityId()) : null;
                Erc3643Suite suite = entry != null ? suites.get(entry.getSuiteId()) : null;
                AssetDeployment deployment = suite != null ? deployments.get(suite.getAssetDeploymentId()) : null;
                Asset asset = deployment != null ? registryAssets.get(deployment.getAssetId()) : null;
                String displayName = entity != null ? entity.getCurrentName() : entry != null ? entry.getWalletAddress() : "Identity registry entry";
                String context = joinNonBlank(
                        asset != null ? asset.getName() : null,
                        entry != null ? entry.getWalletAddress() : null);
                yield new CompanyExternalReferenceLookupResponse(
                        reference.getSubjectType(),
                        reference.getSubjectId(),
                        reference.getExternalId(),
                        displayName,
                        context,
                        asset != null ? asset.getId() : null,
                        reference.getUpdatedAt());
            }
        };
    }

    private List<UUID> subjectIds(List<CompanyExternalReference> references, ExternalReferenceSubjectType subjectType) {
        return references.stream()
                .filter(reference -> reference.getSubjectType() == subjectType)
                .map(CompanyExternalReference::getSubjectId)
                .distinct()
                .toList();
    }

    private Asset resolveAssetForIdentityEntry(Erc3643IdentityRegistry entry) {
        Erc3643Suite suite = suiteRepository.findById(entry.getSuiteId()).orElse(null);
        if (suite == null) {
            return null;
        }
        AssetDeployment deployment = assetDeploymentRepository.findById(suite.getAssetDeploymentId()).orElse(null);
        if (deployment == null) {
            return null;
        }
        return assetRepository.findById(deployment.getAssetId()).orElse(null);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String joinNonBlank(String left, String right) {
        List<String> values = java.util.stream.Stream.of(left, right)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .toList();
        return values.isEmpty() ? null : String.join(" · ", values);
    }
}
