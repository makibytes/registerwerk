package de.makibytes.registerwerk.endpoint.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.RiskLevel;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class EndpointService {

    private final AddressEndpointRepository endpointRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final AssetRepository assetRepository;
    private final LegalEntityRepository legalEntityRepository;

    public EndpointService(
            AddressEndpointRepository endpointRepository,
            AssetHolderRepository assetHolderRepository,
            AssetRepository assetRepository,
            LegalEntityRepository legalEntityRepository) {
        this.endpointRepository = endpointRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.assetRepository = assetRepository;
        this.legalEntityRepository = legalEntityRepository;
    }

    public List<AddressEndpoint> listEndpoints(boolean isOperator, UUID entityId) {
        if (isOperator) {
            return endpointRepository.findByOwnerTypeAndOwnerIdIsNull(AddressEndpoint.OwnerType.OPERATOR);
        }
        return endpointRepository.findByOwnerTypeAndOwnerId(AddressEndpoint.OwnerType.ENTITY, entityId);
    }

    /**
     * Resolves a batch of addresses to human-readable names.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>User-created endpoints (scoped to operator or entity)
     *   <li>KYC'd holder wallet addresses via asset_holder → legal_entity
     * </ol>
     *
     * <p>Operator sees all KYC'd entity wallets; entity users see only wallets
     * from assets they are involved with (as issuer or investor).
     */
    public Map<String, String> resolveAddresses(List<String> addresses, boolean isOperator, UUID entityId) {
        if (addresses == null || addresses.isEmpty()) return Map.of();

        Map<String, String> result = new HashMap<>();
        Set<String> remaining = new HashSet<>(addresses);

        // Step 1: endpoint entries
        List<AddressEndpoint> endpoints = isOperator
                ? endpointRepository.findByOperatorOwnerAndAddressIn(AddressEndpoint.OwnerType.OPERATOR, remaining)
                : endpointRepository.findByEntityOwnerAndAddressIn(AddressEndpoint.OwnerType.ENTITY, entityId, remaining);

        for (AddressEndpoint ep : endpoints) {
            result.put(ep.getAddress(), ep.getName());
            remaining.remove(ep.getAddress());
        }

        if (remaining.isEmpty()) return result;

        // Step 2: asset_holder wallet addresses → entity names
        List<AssetHolder> holders;
        if (isOperator) {
            holders = assetHolderRepository.findByWalletAddressIn(remaining);
        } else {
            List<UUID> relatedAssets = findRelatedAssetIds(entityId);
            holders = relatedAssets.isEmpty()
                    ? List.of()
                    : assetHolderRepository.findByAssetIdInAndWalletAddressIn(relatedAssets, remaining);
        }

        if (!holders.isEmpty()) {
            Set<UUID> investorIds = holders.stream().map(AssetHolder::getInvestorId).collect(Collectors.toSet());
            Map<UUID, String> entityNames = legalEntityRepository.findAllById(investorIds).stream()
                    .collect(Collectors.toMap(LegalEntity::getId, LegalEntity::getCurrentName));

            for (AssetHolder h : holders) {
                if (!result.containsKey(h.getWalletAddress())) {
                    String name = entityNames.get(h.getInvestorId());
                    if (name != null) result.put(h.getWalletAddress(), name);
                }
            }
        }

        return result;
    }

    @Transactional
    public AddressEndpoint createEndpoint(boolean isOperator, UUID entityId, String address,
                                          AddressEndpoint.AddressType addressType, String name,
                                          String notes, RiskLevel riskLevel) {
        boolean duplicate = isOperator
                ? endpointRepository.findByOperatorAndAddress(AddressEndpoint.OwnerType.OPERATOR, address).isPresent()
                : endpointRepository.findByOwnerTypeAndOwnerIdAndAddress(
                        AddressEndpoint.OwnerType.ENTITY, entityId, address).isPresent();

        if (duplicate) {
            throw new IllegalArgumentException("An endpoint for address " + address + " already exists");
        }

        AddressEndpoint ep = new AddressEndpoint();
        ep.setOwnerType(isOperator ? AddressEndpoint.OwnerType.OPERATOR : AddressEndpoint.OwnerType.ENTITY);
        ep.setOwnerId(isOperator ? null : entityId);
        ep.setAddress(address);
        ep.setAddressType(addressType);
        ep.setName(name);
        ep.setNotes(notes);
        ep.setRiskLevel(riskLevel);
        return endpointRepository.save(ep);
    }

    @Transactional
    public AddressEndpoint updateEndpoint(UUID id, boolean isOperator, UUID entityId,
                                          String name, String notes, RiskLevel riskLevel) {
        AddressEndpoint ep = endpointRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("AddressEndpoint", id));
        assertOwner(ep, isOperator, entityId);
        ep.setName(name);
        ep.setNotes(notes);
        ep.setRiskLevel(riskLevel);
        return endpointRepository.save(ep);
    }

    @Transactional
    public void deleteEndpoint(UUID id, boolean isOperator, UUID entityId) {
        AddressEndpoint ep = endpointRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("AddressEndpoint", id));
        assertOwner(ep, isOperator, entityId);
        endpointRepository.delete(ep);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<UUID> findRelatedAssetIds(UUID entityId) {
        Set<UUID> ids = new HashSet<>();
        ids.addAll(assetRepository.findIdsByIssuerId(entityId));
        ids.addAll(assetHolderRepository.findDistinctAssetIdsByInvestorId(entityId));
        return new ArrayList<>(ids);
    }

    private void assertOwner(AddressEndpoint ep, boolean isOperator, UUID entityId) {
        boolean owned = isOperator
                ? ep.getOwnerType() == AddressEndpoint.OwnerType.OPERATOR && ep.getOwnerId() == null
                : ep.getOwnerType() == AddressEndpoint.OwnerType.ENTITY && Objects.equals(ep.getOwnerId(), entityId);
        if (!owned) {
            throw new AccessDeniedException("Cannot modify this endpoint");
        }
    }
}
