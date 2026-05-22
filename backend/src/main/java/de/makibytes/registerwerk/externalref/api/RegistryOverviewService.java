package de.makibytes.registerwerk.externalref.api;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetHolder;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.asset.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.web.dto.RegistryEntityNodeResponse;
import de.makibytes.registerwerk.customer.web.dto.RegistryRelationshipResponse;
import de.makibytes.registerwerk.externalref.web.dto.RegistryOverviewResponse;
import de.makibytes.registerwerk.externalref.web.dto.RegistryOverviewSummaryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class RegistryOverviewService {

    private final LegalEntityRepository legalEntityRepository;
    private final AssetRepository assetRepository;
    private final AssetHolderRepository assetHolderRepository;

    public RegistryOverviewService(
            LegalEntityRepository legalEntityRepository,
            AssetRepository assetRepository,
            AssetHolderRepository assetHolderRepository) {
        this.legalEntityRepository = legalEntityRepository;
        this.assetRepository = assetRepository;
        this.assetHolderRepository = assetHolderRepository;
    }

    public RegistryOverviewResponse getOverview() {
        List<LegalEntity> entities = legalEntityRepository.findAll();
        List<Asset> assets = assetRepository.findAll();
        List<AssetHolder> holders = assetHolderRepository.findAll();

        Map<UUID, Asset> assetById = assets.stream()
                .collect(Collectors.toMap(Asset::getId, asset -> asset));
        Map<UUID, List<Asset>> assetsByIssuer = assets.stream()
                .collect(Collectors.groupingBy(Asset::getIssuerId));
        Map<UUID, List<AssetHolder>> holdingsByInvestor = holders.stream()
                .collect(Collectors.groupingBy(AssetHolder::getInvestorId));
        Map<UUID, EnumSet<EntityType>> rolesByEntity = new HashMap<>();

        for (LegalEntity entity : entities) {
            rolesByEntity.put(entity.getId(), EnumSet.of(entity.getType()));
        }

        for (Asset asset : assets) {
            rolesByEntity.computeIfAbsent(asset.getIssuerId(), ignored -> EnumSet.noneOf(EntityType.class))
                    .add(EntityType.ISSUER);
        }

        Map<String, RelationshipAccumulator> relationshipMap = new LinkedHashMap<>();
        for (AssetHolder holder : holders) {
            Asset asset = assetById.get(holder.getAssetId());
            if (asset == null) {
                continue;
            }

            rolesByEntity.computeIfAbsent(holder.getInvestorId(), ignored -> EnumSet.noneOf(EntityType.class))
                    .add(EntityType.INVESTOR);
            rolesByEntity.computeIfAbsent(asset.getIssuerId(), ignored -> EnumSet.noneOf(EntityType.class))
                    .add(EntityType.ISSUER);

            String key = asset.getId() + ":" + holder.getInvestorId();
            relationshipMap.computeIfAbsent(
                    key,
                    ignored -> new RelationshipAccumulator(asset, holder.getInvestorId())
            ).add(holder);
        }

        List<RegistryRelationshipResponse> relationships = relationshipMap.values().stream()
                .map(RelationshipAccumulator::toResponse)
                .toList();

        Map<UUID, Long> investorLinksByIssuer = relationships.stream()
                .collect(Collectors.groupingBy(RegistryRelationshipResponse::issuerId, Collectors.counting()));
        Map<UUID, Long> issuerLinksByInvestor = relationships.stream()
                .collect(Collectors.groupingBy(RegistryRelationshipResponse::investorId, Collectors.counting()));

        List<RegistryEntityNodeResponse> nodes = entities.stream()
                .map(entity -> new RegistryEntityNodeResponse(
                        entity.getId(),
                        entity.getEntityNumber(),
                        entity.getCurrentName(),
                        entity.getType(),
                        rolesByEntity.getOrDefault(entity.getId(), EnumSet.of(entity.getType())).stream()
                                .sorted((left, right) -> left.compareTo(right))
                                .toList(),
                        entity.getStatus(),
                        entity.getKycStatus(),
                        assetsByIssuer.getOrDefault(entity.getId(), List.of()).size(),
                        holdingsByInvestor.getOrDefault(entity.getId(), List.of()).size(),
                        investorLinksByIssuer.getOrDefault(entity.getId(), 0L),
                        issuerLinksByInvestor.getOrDefault(entity.getId(), 0L)
                ))
                .sorted((left, right) -> left.currentName().compareToIgnoreCase(right.currentName()))
                .toList();

        int issuerCount = (int) nodes.stream().filter(node -> node.roles().contains(EntityType.ISSUER)).count();
        int investorCount = (int) nodes.stream().filter(node -> node.roles().contains(EntityType.INVESTOR)).count();
        int dualRoleCount = (int) nodes.stream()
                .filter(node -> node.roles().contains(EntityType.ISSUER) && node.roles().contains(EntityType.INVESTOR))
                .count();

        return new RegistryOverviewResponse(
                Instant.now(),
                new RegistryOverviewSummaryResponse(
                        nodes.size(),
                        issuerCount,
                        investorCount,
                        dualRoleCount,
                        relationships.size()
                ),
                nodes,
                relationships
        );
    }

    private static final class RelationshipAccumulator {
        private final Asset asset;
        private final UUID investorId;
        private BigDecimal nominalAmount = BigDecimal.ZERO;
        private boolean whitelisted;

        private RelationshipAccumulator(Asset asset, UUID investorId) {
            this.asset = asset;
            this.investorId = investorId;
        }

        private void add(AssetHolder holder) {
            nominalAmount = nominalAmount.add(holder.getNominalAmount() == null ? BigDecimal.ZERO : holder.getNominalAmount());
            whitelisted = whitelisted || Boolean.TRUE.equals(holder.getWhitelisted());
        }

        private RegistryRelationshipResponse toResponse() {
            return new RegistryRelationshipResponse(
                    asset.getId(),
                    asset.getAssetNumber(),
                    asset.getName(),
                    asset.getStatus(),
                    asset.getIssuerId(),
                    investorId,
                    nominalAmount,
                    whitelisted
            );
        }
    }
}
