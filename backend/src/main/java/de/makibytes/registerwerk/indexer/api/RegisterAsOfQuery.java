package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Chain-derived register balances <em>as of</em> an instant (T3-06): nets the FINALIZED indexed
 * transfers with {@code occurred_at < cutoff} over every deployment of an asset. The corporate-action
 * record-date snapshot uses this instead of the live {@code asset_holder.nominal_amount}, so a
 * transfer after the record date no longer moves an entitlement.
 *
 * <p>Same counting rules as {@link HolderDataService}: only FINALIZED rows move a balance, the
 * zero/blank address is the mint/burn counterparty, and a null amount counts as one unit only for
 * ERC-721. Fail-closed where the holder sync uses other sources: ERC-3525 balances are slot
 * values from the subgraph projection (current only), so no as-of position can be derived from
 * transfers; a FINALIZED transfer without amount on any other non-ERC-721 standard is unsupported.
 * Both are reported via {@link AsOfBalances#unsupportedReason()}, never silently netted.
 * TODO: share one netting helper with {@link HolderDataService} (Phase 4).
 *
 * <p>H7: transfers whose {@code deployment_id} is still NULL (indexed before their deployment was linked, see the
 * link-repair in {@code TokenTransferRepository}) belong to the asset when their {@code asset_id} names it or their
 * (chain, contract address) is one of its deployments while their own asset id is unset. They count - for the balances and for the not-final check -
 * instead of silently dropping out of the snapshot; how many were attributed this way is reported in
 * {@link AsOfBalances#unlinkedAttributed()}.
 *
 * <p>{@link AsOfBalances#unfinalizedBeforeCutoff()} counts PROVISIONAL/SAFE transfers before the
 * cut-off: while any exist, the as-of balance is not final yet and the caller must not snapshot.
 * Whether the indexer has <em>seen</em> the chain past the cut-off at all is the caller's check
 * (the asset's last successful holder sync against the cut-off plus a margin).
 */
@Component
public class RegisterAsOfQuery {

    /**
     * @param balances                 net balance per lower-cased wallet (0x addresses; other
     *                                 chains' addresses are lower-cased as map keys too, matching
     *                                 {@link HolderDataService})
     * @param unfinalizedBeforeCutoff  PROVISIONAL/SAFE transfers with {@code occurred_at < cutoff}
     * @param unsupportedReason        non-null when the as-of position cannot be reconstructed from
     *                                 transfers for this asset (the caller must not snapshot)
     * @param unlinkedAttributed       FINALIZED transfers with a NULL deployment that were attributed to the asset
     *                                 by asset id / contract address and netted (H7)
     */
    public record AsOfBalances(Map<String, BigDecimal> balances, long unfinalizedBeforeCutoff,
                               String unsupportedReason, long unlinkedAttributed) {

        public AsOfBalances(Map<String, BigDecimal> balances, long unfinalizedBeforeCutoff) {
            this(balances, unfinalizedBeforeCutoff, null, 0);
        }

        public AsOfBalances(Map<String, BigDecimal> balances, long unfinalizedBeforeCutoff, String unsupportedReason) {
            this(balances, unfinalizedBeforeCutoff, unsupportedReason, 0);
        }
    }

    private static final java.util.Set<TokenStandard> VALUE_STANDARDS =
            java.util.EnumSet.of(TokenStandard.ERC3525, TokenStandard.STARKNET_ERC3525);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetLookupPort assetLookupPort;

    @PersistenceContext
    private EntityManager entityManager;

    public RegisterAsOfQuery(AssetDeploymentRepository deploymentRepository, AssetLookupPort assetLookupPort) {
        this.deploymentRepository = deploymentRepository;
        this.assetLookupPort = assetLookupPort;
    }

    /** True when the asset's register is chain-derived (it has at least one deployment). */
    @Transactional(readOnly = true)
    public boolean isChainDeployed(UUID assetId) {
        return !deploymentRepository.findByAssetId(assetId).isEmpty();
    }

    @Transactional(readOnly = true)
    public AsOfBalances balancesAsOf(UUID assetId, Instant cutoff) {
        List<UUID> deploymentIds = deploymentRepository.findByAssetId(assetId).stream()
                .map(AssetDeployment::getId).toList();
        Map<String, BigDecimal> balances = new HashMap<>();
        if (deploymentIds.isEmpty()) {
            return new AsOfBalances(balances, 0);
        }
        TokenStandard standard = assetLookupPort.findById(assetId)
                .map(AssetLookupPort.AssetInfo::tokenStandard).orElse(null);
        if (standard != null && VALUE_STANDARDS.contains(standard)) {
            return new AsOfBalances(balances, 0, "as-of position of a " + standard + " asset cannot be "
                    + "reconstructed from transfers (balances are slot values, current state only)");
        }
        long withoutAmount = 0;
        long unlinked = 0;
        List<AssetDeployment> deployments = deploymentRepository.findByAssetId(assetId);
        java.util.Set<UUID> chains = new java.util.HashSet<>();
        java.util.Set<String> contracts = new java.util.HashSet<>();
        for (AssetDeployment d : deployments) {
            if (d.getChainConfigId() != null && d.getContractAddress() != null && !d.getContractAddress().isBlank()) {
                chains.add(d.getChainConfigId());
                contracts.add(d.getContractAddress().toLowerCase(Locale.ROOT));
            }
        }
        // H7: rows with deployment_id NULL are matched by asset id, or by (chain, contract) of a deployment.
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT t.fromAddress, t.toAddress, t.amount, t.deploymentId, t.chainConfigId, t.contractAddress "
                                + "FROM TokenTransfer t "
                                + "WHERE t.finalityStatus = :finalized AND t.occurredAt < :cutoff "
                                + "AND (t.deploymentId IN :deployments OR (t.deploymentId IS NULL AND t.assetId = :assetId))",
                        Object[].class)
                .setParameter("deployments", deploymentIds)
                .setParameter("assetId", assetId)
                .setParameter("finalized", FinalityLevel.FINALIZED)
                .setParameter("cutoff", cutoff)
                .getResultList();
        List<Object[]> byContract = chains.isEmpty() ? List.of() : entityManager.createQuery(
                        "SELECT t.fromAddress, t.toAddress, t.amount, t.deploymentId, t.chainConfigId, t.contractAddress "
                                + "FROM TokenTransfer t "
                                + "WHERE t.finalityStatus = :finalized AND t.occurredAt < :cutoff AND t.deploymentId IS NULL "
                                + "AND t.assetId IS NULL "
                                + "AND t.chainConfigId IN :chains AND LOWER(t.contractAddress) IN :contracts",
                        Object[].class)
                .setParameter("finalized", FinalityLevel.FINALIZED)
                .setParameter("cutoff", cutoff)
                .setParameter("chains", chains)
                .setParameter("contracts", contracts)
                .getResultList();
        List<Object[]> all = new java.util.ArrayList<>(rows);
        all.addAll(byContract);
        for (Object[] row : all) {
            if (row[3] == null) {
                unlinked++;
            }
            BigDecimal amount = (BigDecimal) row[2];
            if (amount == null) {
                if (standard != TokenStandard.ERC721) {
                    withoutAmount++;
                    continue;
                }
                amount = BigDecimal.ONE;
            }
            apply(balances, (String) row[0], amount.negate());
            apply(balances, (String) row[1], amount);
        }
        Long unfinalized = entityManager.createQuery(
                        "SELECT COUNT(t) FROM TokenTransfer t WHERE t.finalityStatus IN :pending AND t.occurredAt < :cutoff "
                                + "AND (t.deploymentId IN :deployments OR (t.deploymentId IS NULL AND t.assetId = :assetId))",
                        Long.class)
                .setParameter("deployments", deploymentIds)
                .setParameter("assetId", assetId)
                .setParameter("pending", List.of(FinalityLevel.PROVISIONAL, FinalityLevel.SAFE))
                .setParameter("cutoff", cutoff)
                .getSingleResult();
        long unfinalizedUnlinkedByContract = chains.isEmpty() ? 0 : entityManager.createQuery(
                        "SELECT COUNT(t) FROM TokenTransfer t WHERE t.finalityStatus IN :pending AND t.occurredAt < :cutoff "
                                + "AND t.deploymentId IS NULL AND t.assetId IS NULL "
                                + "AND t.chainConfigId IN :chains AND LOWER(t.contractAddress) IN :contracts",
                        Long.class)
                .setParameter("pending", List.of(FinalityLevel.PROVISIONAL, FinalityLevel.SAFE))
                .setParameter("cutoff", cutoff)
                .setParameter("chains", chains)
                .setParameter("contracts", contracts)
                .getSingleResult();
        unfinalized = (unfinalized != null ? unfinalized : 0) + unfinalizedUnlinkedByContract;
        String unsupported = withoutAmount > 0
                ? withoutAmount + " finalized transfer(s) without amount before the cut-off on a " + standard
                        + " asset — only ERC-721 transfers may omit the amount"
                : null;
        return new AsOfBalances(balances, unfinalized, unsupported, unlinked);
    }

    private static void apply(Map<String, BigDecimal> balances, String address, BigDecimal delta) {
        if (isMintBurnCounterparty(address)) {
            return;
        }
        balances.merge(address.toLowerCase(Locale.ROOT), delta, BigDecimal::add);
    }

    private static boolean isMintBurnCounterparty(String address) {
        if (address == null || address.isBlank()) {
            return true;
        }
        String stripped = address.startsWith("0x") ? address.substring(2) : address;
        return stripped.chars().allMatch(c -> c == '0');
    }
}
