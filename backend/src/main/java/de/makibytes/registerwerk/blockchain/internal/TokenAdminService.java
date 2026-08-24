package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.travelrule.api.TravelRuleGate;

/**
 * Registry-operator administrative controls for ERC-20, ERC-721, and ERC-1155 token contracts.
 *
 * <p>All write operations submit transactions asynchronously and return a tracking UUID.
 * The client polls {@code GET /api/v1/transactions/{txId}} for status. Every operation also
 * publishes a {@link TokenAdminActionEvent} to the tamper-evident audit log (eWpRV §6) via
 * {@link #submitAdmin}, the single chokepoint all of these calls funnel through.
 *
 * <p>Regulatory basis:
 * <ul>
 *   <li>{@link #pause} / {@link #unpause}     — MiCAR Art. 36, 84; eWpG §24</li>
 *   <li>{@link #freezeAddress}                — AWG §17, GwG §40; MiCAR Art. 36</li>
 *   <li>{@link #unfreezeAddress}              — lift sanctions freeze</li>
 *   <li>{@link #whitelist}                    — add to on-chain transfer whitelist</li>
 *   <li>{@link #unwhitelist}                  — remove from on-chain transfer whitelist</li>
 *   <li>{@link #forcedTransfer}               — eWpG §24 Berichtigung (BaFin/court order)</li>
 *   <li>{@link #forcedTransferSingle}         — ERC-1155: forced transfer of specific token id</li>
 *   <li>{@link #forcedApprove}                — regulatory approval override</li>
 *   <li>{@link #forceBurn}                    — eWpG §26 Einziehung (compulsory cancellation)</li>
 *   <li>{@link #forceBurnSingle}              — ERC-1155: forced burn of specific token id</li>
 *   <li>{@link #setSupplyCap}                 — MiCAR Art. 46 (regulatory issuance ceiling)</li>
 * </ul>
 */
@Service
@Transactional
public class TokenAdminService implements TokenAdminPort {

    private static final Logger log = LoggerFactory.getLogger(TokenAdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetLookupPort assetLookupPort;
    private final EvmContractService evmContractService;
    private final de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway durableTransactions;
    private final BlockchainTransactionService txService;
    private final TravelRuleGate travelRuleGate;
    private final HolderBlockGate holderBlockGate;
    private final ApplicationEventPublisher eventPublisher;
    private final ZamaRelayerClient zamaRelayerClient;

    public TokenAdminService(
            AssetDeploymentRepository deploymentRepository,
            AssetLookupPort assetLookupPort,
            EvmContractService evmContractService,
            de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway durableTransactions,
            BlockchainTransactionService txService,
            TravelRuleGate travelRuleGate,
            HolderBlockGate holderBlockGate,
            ApplicationEventPublisher eventPublisher,
            ZamaRelayerClient zamaRelayerClient) {
        this.deploymentRepository = deploymentRepository;
        this.assetLookupPort = assetLookupPort;
        this.evmContractService = evmContractService;
        this.durableTransactions = durableTransactions;
        this.txService = txService;
        this.travelRuleGate = travelRuleGate;
        this.holderBlockGate = holderBlockGate;
        this.eventPublisher = eventPublisher;
        this.zamaRelayerClient = zamaRelayerClient;
    }

    // ── Pause / Unpause (MiCAR Art. 36, 84; eWpG §24) ────────────────────────

    public UUID pause(UUID deploymentId, UUID actorId, String actorRole) {
        log.info("ADMIN pause on deployment={}", deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        return submitAdmin(dep, asset, new Function("pause", Collections.emptyList(), Collections.emptyList()),
                "pause", Map.of(), actorId, actorRole);
    }

    public UUID unpause(UUID deploymentId, UUID actorId, String actorRole) {
        log.info("ADMIN unpause on deployment={}", deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        return submitAdmin(dep, asset, new Function("unpause", Collections.emptyList(), Collections.emptyList()),
                "unpause", Map.of(), actorId, actorRole);
    }

    // ── Address freeze (AWG §17, GwG §40; MiCAR Art. 36) ─────────────────────

    public UUID freezeAddress(UUID deploymentId, String walletAddress, String reason, String legalBasis,
                               UUID actorId, String actorRole) {
        log.info("ADMIN freezeAddress={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        Function fn = new Function("freezeAddress",
                Arrays.asList(new Address(walletAddress), new Utf8String(reason)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "freezeAddress",
                Map.of("address", walletAddress, "reason", reason, "legalBasis", legalBasis), actorId, actorRole);
    }

    public UUID unfreezeAddress(UUID deploymentId, String walletAddress, UUID actorId, String actorRole) {
        log.info("ADMIN unfreezeAddress={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        Function fn = new Function("unfreezeAddress",
                Collections.singletonList(new Address(walletAddress)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "unfreezeAddress", Map.of("address", walletAddress), actorId, actorRole);
    }

    // ── Whitelist management ──────────────────────────────────────────────────

    /** Adds {@code walletAddress} to the on-chain transfer whitelist. */
    public UUID whitelist(UUID deploymentId, String walletAddress, UUID actorId, String actorRole) {
        log.info("ADMIN whitelist={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        requireNotBlocked(walletAddress);
        Function fn = new Function("whitelist",
                Collections.singletonList(new Address(walletAddress)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "whitelist", Map.of("address", walletAddress), actorId, actorRole);
    }

    /** Removes {@code walletAddress} from the on-chain transfer whitelist. */
    public UUID unwhitelist(UUID deploymentId, String walletAddress, UUID actorId, String actorRole) {
        log.info("ADMIN removeFromWhitelist={} on deployment={}", walletAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        Function fn = new Function("removeFromWhitelist",
                Collections.singletonList(new Address(walletAddress)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "removeFromWhitelist", Map.of("address", walletAddress), actorId, actorRole);
    }

    // ── Forced transfer — eWpG §24 Berichtigung ───────────────────────────────

    public UUID forcedTransfer(UUID deploymentId, String from, String to, BigInteger value, String legalBasis,
                               UUID actorId, String actorRole) {
        log.info("ADMIN forcedTransfer from={} to={} value={} on deployment={}", from, to, value, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        if (asset.tokenStandard() == TokenStandard.ERC1155) {
            throw new IllegalArgumentException(
                    "forcedTransfer is not available for ERC-1155 tokens (it would only target token id 0) "
                    + "— use forcedTransferSingle to target a specific token id");
        }
        requireNotBlocked(from);
        requireNotBlocked(to);
        // TFR Reg (EU) 2023/1113: dispatch/record Travel Rule information before
        // submitting the on-chain transfer. EUR valuation is not available at this
        // layer — null is treated conservatively by the gate.
        travelRuleGate.enforceOutbound(dep.getAssetId(), from, to, null);
        Function fn = new Function(
                forcedTransferMethodName(asset.tokenStandard()),
                Arrays.asList(new Address(from), new Address(to), new Uint256(value), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forcedTransfer",
                Map.of("from", from, "to", to, "value", value.toString(), "legalBasis", legalBasis),
                actorId, actorRole);
    }

    /**
     * ERC-1155 only: forces transfer of {@code amount} of token {@code id} from {@code from} to {@code to}.
     */
    public UUID forcedTransferSingle(UUID deploymentId, String from, String to,
                                     BigInteger id, BigInteger amount, String legalBasis,
                                     UUID actorId, String actorRole) {
        log.info("ADMIN forcedTransferSingle id={} amount={} on deployment={}", id, amount, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        requireNotBlocked(from);
        requireNotBlocked(to);
        travelRuleGate.enforceOutbound(dep.getAssetId(), from, to, null);
        if (asset.tokenStandard() != TokenStandard.ERC1155) {
            throw new IllegalArgumentException("forcedTransferSingle is only available for ERC-1155 tokens");
        }
        Function fn = new Function("forcedTransferSingle",
                Arrays.asList(new Address(from), new Address(to), new Uint256(id),
                        new Uint256(amount), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forcedTransferSingle",
                Map.of("from", from, "to", to, "id", id.toString(),
                        "amount", amount.toString(), "legalBasis", legalBasis),
                actorId, actorRole);
    }

    // ── Forced approve ────────────────────────────────────────────────────────

    public UUID forcedApprove(UUID deploymentId, String owner, String spender, BigInteger value, String legalBasis,
                              UUID actorId, String actorRole) {
        log.info("ADMIN forcedApprove owner={} spender={} value={} on deployment={}", owner, spender, value, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        requireNotBlocked(owner);
        Function fn = new Function(
                forcedApproveMethodName(asset.tokenStandard()),
                Arrays.asList(new Address(owner), new Address(spender), new Uint256(value), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forcedApprove",
                Map.of("owner", owner, "spender", spender, "value", value.toString(), "legalBasis", legalBasis),
                actorId, actorRole);
    }

    // ── Forced burn — eWpG §26 Einziehung ────────────────────────────────────

    @Override
    public UUID forceBurn(UUID deploymentId, String from, BigInteger value, String legalBasis,
                          UUID actorId, String actorRole) {
        log.info("ADMIN forceBurn from={} value={} on deployment={}", from, value, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        if (asset.tokenStandard() == TokenStandard.ERC1155) {
            throw new IllegalArgumentException(
                    "forceBurn is not available for ERC-1155 tokens (it would only target token id 0) "
                    + "— use forceBurnSingle to target a specific token id");
        }
        requireNotBlocked(from);
        Function fn = new Function("forceBurn",
                Arrays.asList(new Address(from), new Uint256(value), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forceBurn",
                Map.of("from", from, "value", value.toString(), "legalBasis", legalBasis), actorId, actorRole);
    }

    /**
     * ERC-1155 only: forces burn of {@code amount} of token {@code id} from {@code from}.
     */
    @Override
    public UUID forceBurnSingle(UUID deploymentId, String from, BigInteger id, BigInteger amount, String legalBasis,
                                UUID actorId, String actorRole) {
        log.info("ADMIN forceBurnSingle id={} amount={} on deployment={}", id, amount, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        if (asset.tokenStandard() != TokenStandard.ERC1155) {
            throw new IllegalArgumentException("forceBurnSingle is only available for ERC-1155 tokens");
        }
        requireNotBlocked(from);
        Function fn = new Function("forceBurnSingle",
                Arrays.asList(new Address(from), new Uint256(id), new Uint256(amount), new Utf8String(legalBasis)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "forceBurnSingle",
                Map.of("from", from, "id", id.toString(), "amount", amount.toString(), "legalBasis", legalBasis),
                actorId, actorRole);
    }

    // ── Confidential force-burn (eWpG §26 Einziehung, ERC-7984 encrypted amounts) ─────────────

    /**
     * Forced burn on a confidential (Zama fhEVM) token.
     * {@code ConfidentialERC20.confidentialBurn}/{@code ConfidentialERC3643.confidentialBurn}
     * are already {@code onlyOwner}/{@code onlyAgent}-gated — i.e. already exactly the forced-burn
     * primitive, no separate contract method is needed. What this adds is encrypting
     * {@code amount} into a ciphertext handle + input proof before submitting it, via the
     * {@link ZamaRelayerClient} (a companion relayer sidecar — see that interface's class-level
     * note on what is and is not verified end-to-end here).
     */
    public UUID confidentialForceBurn(UUID deploymentId, String from, BigInteger amount, String legalBasis,
                                       UUID actorId, String actorRole) {
        log.info("ADMIN confidentialForceBurn from={} on deployment={}", from, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = assetLookupPort.findById(dep.getAssetId())
                .orElseThrow(() -> new de.makibytes.registerwerk.shared.EntityNotFoundException("Asset", dep.getAssetId()));
        if (asset.tokenStandard() != TokenStandard.CONF_ERC20 && asset.tokenStandard() != TokenStandard.CONF_ERC3643) {
            throw new IllegalArgumentException("confidentialForceBurn is only available for confidential token standards");
        }
        if (!zamaRelayerClient.isConfigured()) {
            throw new IllegalStateException(
                    "Confidential force-burn requires a configured Zama relayer sidecar "
                    + "(registerwerk.zama.relayer-url) to encrypt the burn amount.");
        }
        requireNotBlocked(from);
        String operatorAddress = evmContractService.signer(
                new ChainDescriptor(dep.getChain(), dep.getNetwork())).address();
        ZamaRelayerClient.EncryptedInput encrypted =
                zamaRelayerClient.encryptInput(dep.getContractAddress(), operatorAddress, amount);

        Function fn = new Function("confidentialBurn",
                Arrays.asList(
                        new Address(from),
                        new org.web3j.abi.datatypes.generated.Bytes32(
                                org.web3j.utils.Numeric.hexStringToByteArray(encrypted.ciphertextHandle())),
                        new org.web3j.abi.datatypes.DynamicBytes(
                                org.web3j.utils.Numeric.hexStringToByteArray(encrypted.inputProofHex()))
                ),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "confidentialForceBurn",
                Map.of("from", from, "amount", amount.toString(), "legalBasis", legalBasis), actorId, actorRole);
    }

    // ── Confidential issuance (issuer/operator minting, encrypted amounts) ───────────────────

    /**
     * Confidential mint — the ERC-7984/ERC-3643 encrypted-amount equivalent of {@link #mint}.
     * Same encrypt-then-submit shape as {@link #confidentialForceBurn}: the amount is encrypted
     * server-side via {@link ZamaRelayerClient} (there is no browser/wallet in an
     * issuer-initiated mint any more than in an operator-initiated force-burn — an investor's own
     * confidential TRANSFER, by contrast, is encrypted client-side by the frontend and never
     * reaches this method).
     */
    public UUID confidentialMint(UUID deploymentId, String toAddress, BigInteger amount,
                                  UUID actorId, String actorRole) {
        log.info("ISSUER confidentialMint to={} on deployment={}", toAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = assetLookupPort.findById(dep.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", dep.getAssetId()));
        if (asset.tokenStandard() != TokenStandard.CONF_ERC20 && asset.tokenStandard() != TokenStandard.CONF_ERC3643) {
            throw new IllegalArgumentException("confidentialMint is only available for confidential token standards");
        }
        if (!zamaRelayerClient.isConfigured()) {
            throw new IllegalStateException(
                    "Confidential mint requires a configured Zama relayer sidecar "
                    + "(registerwerk.zama.relayer-url) to encrypt the mint amount.");
        }
        requireNotBlocked(toAddress);
        String operatorAddress = evmContractService.signer(
                new ChainDescriptor(dep.getChain(), dep.getNetwork())).address();
        ZamaRelayerClient.EncryptedInput encrypted =
                zamaRelayerClient.encryptInput(dep.getContractAddress(), operatorAddress, amount);

        Function fn = new Function("confidentialMint",
                Arrays.asList(
                        new Address(toAddress),
                        new org.web3j.abi.datatypes.generated.Bytes32(
                                org.web3j.utils.Numeric.hexStringToByteArray(encrypted.ciphertextHandle())),
                        new org.web3j.abi.datatypes.DynamicBytes(
                                org.web3j.utils.Numeric.hexStringToByteArray(encrypted.inputProofHex()))
                ),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "confidentialMint",
                Map.of("toAddress", toAddress, "amount", amount.toString()), actorId, actorRole);
    }

    // ── Confidential viewer ACL (who besides the holder can decrypt a balance) ───────────────

    /**
     * Grants {@code viewerAddress} decrypt rights on every holder's (and total supply's) FUTURE
     * balance handle on this confidential token — see {@code ConfidentialERC20.addViewer}'s doc
     * comment for the "additive, not retroactive" ACL semantics. Typically used to add an
     * auditor, or an issuer's own wallet, after deployment (the operator's and initially
     * configured auditor's viewer addresses are already granted at deploy time — see
     * {@code ContractAddressConfig.confidentialInitialViewers}).
     */
    public UUID confidentialAddViewer(UUID deploymentId, String viewerAddress, UUID actorId, String actorRole) {
        log.info("ADMIN confidentialAddViewer={} on deployment={}", viewerAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireConfidentialToken(dep);
        Function fn = new Function("addViewer",
                Collections.singletonList(new Address(viewerAddress)), Collections.emptyList());
        return submitAdmin(dep, asset, fn, "confidentialAddViewer", Map.of("viewer", viewerAddress), actorId, actorRole);
    }

    /**
     * Stops granting {@code viewerAddress} decrypt rights on FUTURE balance mutations — does NOT
     * revoke already-granted access to existing ciphertext handles (Zama's ACL has no revoke
     * primitive; see {@code ConfidentialERC20.removeViewer}'s doc comment).
     */
    public UUID confidentialRemoveViewer(UUID deploymentId, String viewerAddress, UUID actorId, String actorRole) {
        log.info("ADMIN confidentialRemoveViewer={} on deployment={}", viewerAddress, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireConfidentialToken(dep);
        Function fn = new Function("removeViewer",
                Collections.singletonList(new Address(viewerAddress)), Collections.emptyList());
        return submitAdmin(dep, asset, fn, "confidentialRemoveViewer", Map.of("viewer", viewerAddress), actorId, actorRole);
    }

    private AssetLookupPort.AssetInfo requireConfidentialToken(AssetDeployment dep) {
        AssetLookupPort.AssetInfo asset = assetLookupPort.findById(dep.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", dep.getAssetId()));
        if (asset.tokenStandard() != TokenStandard.CONF_ERC20 && asset.tokenStandard() != TokenStandard.CONF_ERC3643) {
            throw new IllegalArgumentException("This operation is only available for confidential token standards");
        }
        return asset;
    }

    // ── Confidential ERC-3643 pause / freeze  ───────────
    //
    // ConfidentialERC3643.sol implements pause()/unpause()/setAddressFrozen(address,bool) — the
    // eWpG §24/MiCAR Art. 36,84 corrective-action machinery every OTHER T-REX-family token
    // already exposes here — but confidential deployments were previously routed to neither this
    // class's plaintext pause()/freezeAddress() (requireEvmToken() explicitly rejects
    // CONF_ERC3643, redirecting it to Erc3643LifecycleService) nor Erc3643LifecycleService
    // (which requires an Erc3643Suite row that confidential deployments — deployed via
    // EwpgConfidentialFactory, not EwpgTREXFactory — never have), leaving them operationally
    // unreachable from either admin surface. Unlike confidentialForceBurn/confidentialMint,
    // these three functions take no encrypted arguments, so no relayer round-trip is needed.

    public UUID confidentialPause(UUID deploymentId, UUID actorId, String actorRole) {
        log.info("ADMIN confidentialPause on deployment={}", deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireConfidentialErc3643Token(dep);
        Function fn = new Function("pause", Collections.emptyList(), Collections.emptyList());
        return submitAdmin(dep, asset, fn, "confidentialPause", Map.of(), actorId, actorRole);
    }

    public UUID confidentialUnpause(UUID deploymentId, UUID actorId, String actorRole) {
        log.info("ADMIN confidentialUnpause on deployment={}", deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireConfidentialErc3643Token(dep);
        Function fn = new Function("unpause", Collections.emptyList(), Collections.emptyList());
        return submitAdmin(dep, asset, fn, "confidentialUnpause", Map.of(), actorId, actorRole);
    }

    /**
     * ConfidentialERC3643.setAddressFrozen(address,bool) toggles freeze state via a single
     * function (unlike the plaintext freezeAddress/unfreezeAddress pair), so one method covers
     * both directions.
     */
    public UUID confidentialSetAddressFrozen(UUID deploymentId, String walletAddress, boolean frozen,
                                             UUID actorId, String actorRole) {
        log.info("ADMIN confidentialSetAddressFrozen={} frozen={} on deployment={}",
                walletAddress, frozen, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireConfidentialErc3643Token(dep);
        Function fn = new Function("setAddressFrozen",
                Arrays.asList(new Address(walletAddress), new Bool(frozen)), Collections.emptyList());
        return submitAdmin(dep, asset, fn, "confidentialSetAddressFrozen",
                Map.of("address", walletAddress, "frozen", frozen), actorId, actorRole);
    }

    private AssetLookupPort.AssetInfo requireConfidentialErc3643Token(AssetDeployment dep) {
        AssetLookupPort.AssetInfo asset = assetLookupPort.findById(dep.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", dep.getAssetId()));
        if (asset.tokenStandard() != TokenStandard.CONF_ERC3643) {
            throw new IllegalArgumentException("This operation is only available for confidential ERC-3643 tokens");
        }
        return asset;
    }

    // ── Standard issuance (issuer minting/burning) ────────────────────────────

    public UUID mint(UUID deploymentId, String toAddress, BigInteger amount, UUID actorId, String actorRole) {
        log.info("Issuer MINT to={} amount={} on deployment={}", toAddress, amount, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        requireNotBlocked(toAddress);
        Function fn = new Function("mint",
                Arrays.asList(new Address(toAddress), new Uint256(amount)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "mint", Map.of("toAddress", toAddress, "amount", amount.toString()),
                actorId, actorRole);
    }

    public UUID regularBurn(UUID deploymentId, String fromAddress, BigInteger amount, UUID actorId, String actorRole) {
        log.info("Issuer BURN from={} amount={} on deployment={}", fromAddress, amount, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        Function fn = new Function("burn",
                Arrays.asList(new Address(fromAddress), new Uint256(amount)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "burn", Map.of("fromAddress", fromAddress, "amount", amount.toString()),
                actorId, actorRole);
    }

    // ── Supply cap — MiCAR Art. 46 ────────────────────────────────────────────

    public UUID setSupplyCap(UUID deploymentId, BigInteger newCap, UUID actorId, String actorRole) {
        log.info("ADMIN setSupplyCap={} on deployment={}", newCap, deploymentId);
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetLookupPort.AssetInfo asset = requireEvmToken(dep);
        Function fn = new Function("setSupplyCap",
                Collections.singletonList(new Uint256(newCap)),
                Collections.emptyList());
        return submitAdmin(dep, asset, fn, "setSupplyCap", Map.of("newCap", newCap.toString()), actorId, actorRole);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * §16 eWpG Sperrvermerk — a wallet under an active legal block (court order, pledge,
     * insolvency, etc.) must not be whitelisted, minted to, or have tokens moved/burned/approved
     * on its behalf, regardless of KYC/screening status. Checked by wallet address only: at this
     * layer the caller does not always have the corresponding {@code LegalEntity} on hand, and a
     * block may itself be registered against only a wallet address (see {@link HolderBlockGate}).
     */
    private void requireNotBlocked(String walletAddress) {
        if (holderBlockGate.isBlocked(null, walletAddress)) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Wallet " + walletAddress + " is subject to an active §16 eWpG Sperrvermerk "
                    + "(legal block) — operation refused.");
        }
    }

    private AssetDeployment requireDeployment(UUID deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
    }

    private AssetLookupPort.AssetInfo requireEvmToken(AssetDeployment dep) {
        AssetLookupPort.AssetInfo asset = assetLookupPort.findById(dep.getAssetId())
                .orElseThrow(() -> new de.makibytes.registerwerk.shared.EntityNotFoundException("Asset", dep.getAssetId()));
        TokenStandard standard = asset.tokenStandard();
        if (standard == TokenStandard.ERC3643 || standard == TokenStandard.CONF_ERC3643) {
            throw new IllegalArgumentException(
                    "ERC-3643 admin operations go through Erc3643LifecycleService (/erc3643 endpoints).");
        }
        if (standard == TokenStandard.CONF_ERC20) {
            throw new UnsupportedOperationException(
                    "Confidential ERC-20 admin controls require Zama fhEVM support (not yet implemented).");
        }
        if (standard == TokenStandard.SPL || standard == TokenStandard.SPL_2022) {
            throw new UnsupportedOperationException(
                    "SPL token admin controls go through SolanaTokenAdminService (implementation).");
        }
        if (standard == TokenStandard.SPL_2022_BOND || standard == TokenStandard.SPL_2022_CONFIDENTIAL) {
            throw new UnsupportedOperationException(
                    "Token-2022 extension-preset admin operations go through SolanaTokenAdminService (implementation).");
        }
        if (standard == TokenStandard.ERC3525 || standard == TokenStandard.STARKNET_ERC3525) {
            throw new IllegalArgumentException(
                    "ERC-3525 and Starknet SFT admin operations (slot pause, token freeze, forcedTransferValue) " +
                    "go through Erc3525AdminService (/api/v1/deployments/{id}/slots and /tokens endpoints).");
        }
        if (standard == TokenStandard.ERC4626 || standard == TokenStandard.ERC7540) {
            throw new IllegalArgumentException(
                    "ERC-4626/7540 vault admin operations (NAV strike, request fulfillment) " +
                    "go through Erc4626AdminService and Erc7540AdminService " +
                    "(/api/v1/deployments/{id}/nav-strike and /vault-requests endpoints).");
        }
        if (standard == TokenStandard.DAML_BOND_FIXED || standard == TokenStandard.DAML_BOND_FLOATING
                || standard == TokenStandard.DAML_BOND_ZERO) {
            throw new IllegalArgumentException(
                    "Registerwerk Daml bond lifecycle operations (coupon authorization, rate fixing, redemption, early call) " +
                    "go through CantonBondOperations (/api/v1/deployments/{id}/coupon-payment etc.).");
        }
        if (dep.getContractAddress() == null || dep.getContractAddress().startsWith("0x-PENDING")) {
            throw new IllegalStateException("Token contract is not yet deployed for deployment=" + dep.getId());
        }
        return asset;
    }

    /**
     * Submits the on-chain transaction and publishes a {@link TokenAdminActionEvent} to the
     * audit log — the single chokepoint every public method in this class funnels through,
     * so wiring the audit publish here covers the entire EVM admin/correction surface.
     */
    private UUID submitAdmin(AssetDeployment dep, AssetLookupPort.AssetInfo asset, Function fn, String methodName,
                              Map<String, Object> params, UUID actorId, String actorRole) {
        String txHash = durableTransactions.submit(
                requireChainConfigId(dep), dep.getContractAddress(), fn, params);

        eventPublisher.publishEvent(new TokenAdminActionEvent(dep.getId(), methodName, actorId, actorRole, params));

        return txService.record(txHash, methodName, dep.getId(), asset.id(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
    }

    private UUID requireChainConfigId(AssetDeployment deployment) {
        if (deployment.getChainConfigId() == null) {
            throw new IllegalStateException(
                    "Confirmed EVM deployment is missing chainConfigId: " + deployment.getId());
        }
        return deployment.getChainConfigId();
    }

    private static String forcedTransferMethodName(TokenStandard standard) {
        return switch (standard) {
            case ERC20, ERC721 -> "forcedTransfer";
            // ERC1155 is rejected earlier in forcedTransfer() — it must go through
            // forcedTransferSingle so the caller-supplied token id is honored instead
            // of silently defaulting to id 0.
            default -> throw new UnsupportedOperationException(
                    "Forced transfer method mapping is not defined for token standard: " + standard);
        };
    }

    private static String forcedApproveMethodName(TokenStandard standard) {
        return switch (standard) {
            case ERC20, ERC721, ERC1155 -> "forcedApprove";
            default -> throw new UnsupportedOperationException(
                    "Forced approve method mapping is not defined for token standard: " + standard);
        };
    }
}
