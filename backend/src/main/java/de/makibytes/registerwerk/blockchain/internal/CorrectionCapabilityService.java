package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Single source of truth for "what correction options exist for this deployment" —
 * backs {@code GET /api/v1/assets/{assetId}/deployments/{depId}/corrections} so both
 * frontends render only the corrections actually available for a deployment's chain and
 * token standard, and label each as a reversible cancel vs. a compensating booking that
 * requires a second (opposite) on-chain transaction.
 *
 * <p>Every entry below is cross-checked against the controller that actually exposes it —
 * {@code action} ids are only listed when a real, reachable HTTP endpoint exists today.
 * ERC-3525/4626/7540 route through dedicated admin services
 * ({@code Erc3525SlotController}, {@code VaultController}) with a DIFFERENT, narrower set of
 * corrections than the generic ERC-20/721/1155 surface ({@code TokenAdminController}) —
 * {@code TokenAdminService.requireEvmToken} explicitly rejects freeze/pause/forced-transfer/
 * force-burn for these standards, so they must NOT be listed here as if they used it.
 * Standards with a service method but no controller wiring it up (ERC-3525 force-burn-value,
 * ERC-7540 cancel-redeem-request, ERC-3643 recovery) are also deliberately omitted — a
 * capability the frontend can't actually invoke is worse than an honest gap.
 */
@Service
public class CorrectionCapabilityService {

    /**
     * @param action      machine-readable action id, matches the corresponding admin endpoint
     * @param label       human-readable label for the frontend
     * @param cancelable  true if this undoes state in place (e.g. unfreeze); false if it
     *                    requires booking an opposite transaction (e.g. force-burn to undo a mint)
     * @param description short explanation of the legal/technical basis
     */
    public record CorrectionCapability(String action, String label, boolean cancelable, String description) {}

    public record CorrectionCapabilitiesResponse(
            UUID deploymentId, TokenStandard tokenStandard, List<CorrectionCapability> capabilities) {}

    // ── ERC-20/721/1155 (TokenAdminController) ──────────────────────────────
    private static final CorrectionCapability FREEZE = new CorrectionCapability(
            "freeze", "Freeze / Unfreeze address", true,
            "AWG §17, GwG §40, MiCAR Art. 36 — reversible in place, no compensating transaction needed.");
    private static final CorrectionCapability PAUSE = new CorrectionCapability(
            "pause", "Pause / Unpause contract", true,
            "MiCAR Art. 36/84, eWpG §24 — reversible in place, halts all transfers.");
    private static final CorrectionCapability FORCED_TRANSFER = new CorrectionCapability(
            "forced-transfer", "Forced transfer (§24 Berichtigung)", false,
            "Moves tokens to a court/BaFin-ordered address. To correct a wrongful forced-transfer, "
                    + "book an opposite forced-transfer — the original entry is never edited.");
    private static final CorrectionCapability FORCED_APPROVE = new CorrectionCapability(
            "forced-approve", "Forced approve override", false,
            "Regulatory approval override — book an opposite forced-approve to correct a wrongful one.");
    private static final CorrectionCapability FORCE_BURN = new CorrectionCapability(
            "force-burn", "Forced burn (§26 Einziehung)", false,
            "Compulsory cancellation of tokens. Irreversible on-chain — correcting a wrongful "
                    + "force-burn requires a fresh mint of equal amount, booked as a distinct transaction.");
    private static final CorrectionCapability FORCE_BURN_CONFIDENTIAL = new CorrectionCapability(
            "force-burn-confidential", "Forced burn — confidential (§26 Einziehung, encrypted amount)", false,
            "Same compulsory-cancellation authority as force-burn, over an FHE-encrypted amount. "
                    + "Requires a configured Zama relayer sidecar to encrypt the amount before submission.");

    // ── ERC-3643 / T-REX additions (Erc3643Controller) ──────────────────────
    private static final CorrectionCapability FREEZE_PARTIAL = new CorrectionCapability(
            "freeze-partial", "Freeze / unfreeze a partial token amount", true,
            "T-REX partial freeze — reversible in place, does not require full address freeze.");
    private static final CorrectionCapability BATCH_FORCED_TRANSFER = new CorrectionCapability(
            "batch-forced-transfer", "Batch forced transfer", false,
            "T-REX agent batch operation — book an opposite batch forced-transfer to correct a wrongful batch.");
    private static final CorrectionCapability BATCH_BURN = new CorrectionCapability(
            "batch-burn", "Batch forced burn", false,
            "T-REX agent batch operation — correcting a wrongful batch burn requires a fresh batch mint.");

    // ── ERC-3525 / Starknet SFT (Erc3525SlotController) ─────────────────────
    private static final CorrectionCapability PAUSE_SLOT = new CorrectionCapability(
            "pause-slot", "Pause / unpause a slot", true, "Reversible in place, halts transfers within the slot.");
    private static final CorrectionCapability FREEZE_TOKEN = new CorrectionCapability(
            "freeze-token", "Freeze / unfreeze a specific token", true, "Reversible in place, per-token freeze.");
    private static final CorrectionCapability FORCED_VALUE_TRANSFER = new CorrectionCapability(
            "forced-value-transfer", "Forced value transfer between tokens", false,
            "Moves slot value between tokens under agent authority — book an opposite transfer to correct.");

    // ── ERC-7540 async vault (VaultController) ──────────────────────────────
    private static final CorrectionCapability CANCEL_DEPOSIT_REQUEST = new CorrectionCapability(
            "cancel-deposit-request", "Cancel a pending deposit request", true,
            "Reversible in place — withdraws a not-yet-fulfilled deposit request before NAV strike.");

    // ── SPL Token-2022 (SolanaTokenAdminController, Permanent Delegate) ──────
    private static final CorrectionCapability FREEZE_SPL = new CorrectionCapability(
            "spl-freeze", "Freeze / thaw token account", true,
            "AWG §17, GwG §40, MiCAR Art. 36 — via the Token-2022 freeze authority; reversible in place.");
    private static final CorrectionCapability FORCED_TRANSFER_SPL = new CorrectionCapability(
            "spl-forced-transfer", "Forced transfer (§24 Berichtigung)", false,
            "Moves tokens via the Token-2022 Permanent Delegate extension, bypassing holder consent. "
                    + "Book an opposite forced-transfer to correct a wrongful one.");
    private static final CorrectionCapability FORCE_BURN_SPL = new CorrectionCapability(
            "spl-force-burn", "Forced burn (§26 Einziehung)", false,
            "Compulsory cancellation via the Token-2022 Permanent Delegate extension. Irreversible "
                    + "on-chain — correcting a wrongful force-burn requires a fresh mint of equal amount.");

    // ── Canton (TokenAdminController Canton-only endpoints) ─────────────────
    private static final CorrectionCapability FREEZE_HOLDING = new CorrectionCapability(
            "freeze-holding", "Freeze / unfreeze a holding", true, "Reversible in place, per-holding freeze.");
    private static final CorrectionCapability FORCE_TRANSFER_CANTON = new CorrectionCapability(
            "force-transfer-canton", "Forced transfer (issuer authority)", false,
            "Moves a holding under issuer authority — book an opposite forced-transfer to correct.");
    private static final CorrectionCapability BURN_HOLDING = new CorrectionCapability(
            "burn-holding", "Burn a holding", false,
            "Irreversible — correcting a wrongful burn requires a fresh issuance of equal amount.");

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetLookupPort assetLookupPort;

    public CorrectionCapabilityService(AssetDeploymentRepository deploymentRepository, AssetLookupPort assetLookupPort) {
        this.deploymentRepository = deploymentRepository;
        this.assetLookupPort = assetLookupPort;
    }

    @Transactional(readOnly = true)
    public CorrectionCapabilitiesResponse getCapabilities(UUID deploymentId) {
        var deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        var asset = assetLookupPort.findById(deployment.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", deployment.getAssetId()));
        TokenStandard standard = asset.tokenStandard();
        return new CorrectionCapabilitiesResponse(deploymentId, standard, capabilitiesFor(standard));
    }

    private static List<CorrectionCapability> capabilitiesFor(TokenStandard standard) {
        return switch (standard) {
            case ERC20, ERC721, ERC1155 ->
                    List.of(FREEZE, PAUSE, FORCED_TRANSFER, FORCED_APPROVE, FORCE_BURN);
            case ERC3643 ->
                    List.of(FREEZE, PAUSE, FORCED_TRANSFER, FREEZE_PARTIAL, FORCE_BURN,
                            BATCH_FORCED_TRANSFER, BATCH_BURN);
            // ConfidentialERC3643.sol itself HAS pause/setAddressFrozen/forcedTransfer methods,
            // but Erc3643LifecycleService/Erc3643Controller (the only wired admin path for
            // ERC3643/CONF_ERC3643 — TokenAdminController explicitly rejects both) is built
            // against the plain EwpgERC3643 ABI (plaintext uint256 amounts); calling it against
            // a ConfidentialERC3643 contract would send mismatched calldata. Only forced-burn
            // has a real, tested, confidential-specific path today
            // (TokenAdminService.confidentialForceBurn) — reporting only that rather than
            // claiming freeze/pause/forced-transfer work when they are not actually wired.
            case CONF_ERC3643 -> List.of(FORCE_BURN_CONFIDENTIAL);
            case ERC3525, STARKNET_ERC3525 ->
                    List.of(PAUSE_SLOT, FREEZE_TOKEN, FORCED_VALUE_TRANSFER);
            // ERC-4626 (sync vault) has no forced-correction endpoint today — only NAV strike
            // and deposit-cap, neither of which corrects a wrongful transfer/mint/burn.
            case ERC4626 -> List.of();
            // ERC-7540 (async vault): only the deposit-request cancel is actually wired up
            // (VaultController never calls Erc7540AdminService.cancelRedeemRequest).
            case ERC7540 -> List.of(CANCEL_DEPOSIT_REQUEST);
            // CIP-0056 standardizes interfaces and registry/wallet workflows; it does not define
            // universal issuer-admin choices. A registry-specific adapter is required before any
            // CANTON_TOKEN correction can be advertised safely.
            case CANTON_TOKEN -> List.of();
            // Registerwerk Daml bonds (CantonBondOperations) expose only scheduled lifecycle events
            // (coupon payment, rate fixing, redemption, early call) — no freeze/pause/forced
            // -transfer/force-burn primitive exists for this standard today.
            case DAML_BOND_FIXED, DAML_BOND_FLOATING, DAML_BOND_ZERO -> List.of();
            // Confidential ERC-20: forced-burn is now real (TokenAdminService.confidentialForceBurn,
            // requires a configured Zama relayer sidecar). Freeze/pause have no equivalent on
            // ConfidentialERC20.sol at all (only ConfidentialERC3643 has them).
            case CONF_ERC20 -> List.of(FORCE_BURN_CONFIDENTIAL);
            // SolanaTokenAdminController wires freeze/thaw + Permanent-Delegate forced-transfer
            // and force-burn for all four SPL Token-2022 variants (the extension presets only
            // change which OTHER extensions are active on the mint, not what the Permanent
            // Delegate / freeze authority can do).
            case SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL ->
                    List.of(FREEZE_SPL, FORCED_TRANSFER_SPL, FORCE_BURN_SPL);
            // StarknetTokenService.freezeAccount/unfreezeAccount and StellarAssetService's
            // clawbackAsset/setAuthorizationFlags exist but neither has controller wiring yet —
            // listed as empty here for the same reason as SPL above, not an oversight.
            case STARKNET_ERC20, STELLAR_ASSET -> List.of();
        };
    }
}
