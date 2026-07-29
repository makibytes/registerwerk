package de.makibytes.registerwerk.blockchain.api;

import com.daml.ledger.javaapi.data.Command;
import com.daml.ledger.javaapi.data.CreateAndExerciseCommand;
import com.daml.ledger.javaapi.data.ExerciseCommand;
import de.makibytes.registerwerk.blockchain.events.CantonInstrumentCreatedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonTokensIssuedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonTransferEvent;
import de.makibytes.registerwerk.blockchain.events.CantonForceTransferEvent;
import de.makibytes.registerwerk.blockchain.events.CantonHoldingLockedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonHoldingUnlockedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonBurnEvent;
import de.makibytes.registerwerk.blockchain.events.CantonInstrumentPausedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonInstrumentResumedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.chain.api.CantonLedgerClient;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.WalletStorage.CantonContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles token lifecycle operations on Canton via the Daml Token Standard (CIP-0056).
 *
 * <p>All operations are delegated to the Canton participant's Ledger API (gRPC).
 * The Daml Token Standard package is assumed to be pre-loaded on the participant
 * (it is deployed on the public Canton Network by default).
 *
 * <p>Method signatures mirror {@link SolanaTokenService} so {@link
 * de.makibytes.registerwerk.application.asset.AssetDeploymentService} can route
 * symmetrically. All methods return {@link CompletableFuture}&lt;String&gt; carrying
 * the Ledger API update ID (analogous to an EVM tx hash or Solana signature).
 *
 * <h3>Daml Token Standard concepts mapped to Registerwerk operations</h3>
 * <ul>
 *   <li>{@code createInstrument} → allocates a new {@code Instrument} contract</li>
 *   <li>{@code issue} → exercises {@code Issue} choice (mint to recipient holding)</li>
 *   <li>{@code transfer} → exercises {@code Transfer} choice between parties</li>
 *   <li>{@code forceTransfer} → exercises issuer's {@code ForceTransfer} admin choice</li>
 *   <li>{@code freezeHolding} / {@code unfreezeHolding} → exercises {@code Lock}/{@code Unlock}</li>
 *   <li>{@code burn} → exercises {@code Burn} choice on a holding</li>
 *   <li>{@code pause} / {@code unpause} → exercises {@code Pause}/{@code Resume} on the instrument</li>
 * </ul>
 */
@Service
public class CantonTokenService implements CantonTokenOperations {

    private static final Logger log = LoggerFactory.getLogger(CantonTokenService.class);

    /**
     * Package ID of the Daml Token Standard on Canton Network.
     * Override via canton token-standard package ID lookup at runtime if package hash changes.
     */
    static final String TOKEN_STANDARD_PACKAGE = "lfdt-token-standard";

    private final BlockchainClientRegistry registry;
    private final ChainConfigRepository chainConfigRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final WalletSigner walletSigner;

    public CantonTokenService(
            BlockchainClientRegistry registry,
            ChainConfigRepository chainConfigRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            ApplicationEventPublisher eventPublisher,
            WalletSigner walletSigner) {
        this.registry                 = registry;
        this.chainConfigRepository    = chainConfigRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.eventPublisher            = eventPublisher;
        this.walletSigner              = walletSigner;
    }

    // ── Instrument creation (= token deployment) ──────────────────────────────

    /**
     * Creates a new Daml Token Standard {@code Instrument} contract on Canton.
     *
     * @param assetId       Registerwerk asset UUID (used in the instrument metadata)
     * @param network       MAINNET or TESTNET (determines which chain_config row to use)
     * @param issuerPartyId Canton party ID of the issuing authority
     * @param decimals      token decimal precision (e.g. 0 for whole units, 6 for micro-units)
     * @return update ID of the committed transaction
     */
    public CompletableFuture<String> createInstrument(
            UUID assetId, Network network, String issuerPartyId, int decimals, UUID actorId, String actorRole) {

        return CompletableFuture.supplyAsync(() -> {
            CantonLedgerClient client = resolveClient(network);
            String identifier = resolveIdentifier(network);

            // Build InstrumentFactory.Create command using Daml Token Standard template.
            // The instrument is created under the issuer's party authority.
            // Template: lfdt.tokenstandard.instrument.InstrumentFactory
            Command createCmd = new CreateAndExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE,
                            "Lfdt.Tokenstandard.Instrument",
                            "InstrumentFactory"),
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of(
                            new com.daml.ledger.javaapi.data.DamlRecord.Field("issuer",
                                    new com.daml.ledger.javaapi.data.Party(issuerPartyId)),
                            new com.daml.ledger.javaapi.data.DamlRecord.Field("assetId",
                                    new com.daml.ledger.javaapi.data.Text(assetId.toString())),
                            new com.daml.ledger.javaapi.data.DamlRecord.Field("decimals",
                                    new com.daml.ledger.javaapi.data.Int64((long) decimals))
                    )),
                    "CreateInstrument",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of()));

            String updateId = client.submitAndWait(issuerPartyId, List.of(createCmd));
            log.info("Canton instrument created: asset={} network={} updateId={}", assetId, network, updateId);

            eventPublisher.publishEvent(new CantonInstrumentCreatedEvent(assetId, actorId, actorRole,
                    Map.of("network", network.name(), "issuerPartyId", issuerPartyId, "decimals", decimals, "updateId", updateId)));
            return updateId;
        });
    }

    // ── Issuance (mint) ───────────────────────────────────────────────────────

    /**
     * Issues tokens to a recipient party (equivalent to mint).
     *
     * @param deploymentId   Registerwerk deployment UUID (to look up the instrument contract ID)
     * @param recipientPartyId Canton party receiving the new holding
     * @param amount         token amount to issue
     * @return update ID
     */
    public CompletableFuture<String> issue(UUID deploymentId, String recipientPartyId, BigDecimal amount,
                                            UUID actorId, String actorRole) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = loadDeployment(deploymentId);
            CantonLedgerClient client  = resolveClientForDeployment(deployment);
            CantonContext ctx           = resolveContext(deployment);

            Command issueCmd = new ExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE,
                            "Lfdt.Tokenstandard.Instrument",
                            "Instrument"),
                    deployment.getContractAddress(),
                    "Issue",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of(
                            field("recipient", new com.daml.ledger.javaapi.data.Party(recipientPartyId)),
                            field("amount",    new com.daml.ledger.javaapi.data.Numeric(amount))
                    )));

            String updateId = client.submitAndWait(ctx.partyId(), List.of(issueCmd));
            log.info("Canton tokens issued: deployment={} recipient={} amount={} updateId={}",
                    deploymentId, recipientPartyId, amount, updateId);

            eventPublisher.publishEvent(new CantonTokensIssuedEvent(deploymentId, actorId, actorRole,
                    Map.of("recipientPartyId", recipientPartyId, "amount", amount.toPlainString(), "updateId", updateId)));
            return updateId;
        });
    }

    // ── Transfer ──────────────────────────────────────────────────────────────

    /**
     * Transfers a holding from one party to another (requires sender's authority).
     */
    public CompletableFuture<String> transfer(
            UUID deploymentId, String holdingContractId,
            String fromParty, String toParty, BigDecimal amount, UUID actorId, String actorRole) {

        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = loadDeployment(deploymentId);
            CantonLedgerClient client  = resolveClientForDeployment(deployment);
            CantonContext ctx           = resolveContext(deployment);

            Command transferCmd = new ExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE,
                            "Lfdt.Tokenstandard.Holding",
                            "Holding"),
                    holdingContractId,
                    "Transfer",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of(
                            field("newOwner", new com.daml.ledger.javaapi.data.Party(toParty)),
                            field("amount",   new com.daml.ledger.javaapi.data.Numeric(amount))
                    )));

            String updateId = client.submitAndWait(fromParty, List.of(transferCmd));
            log.info("Canton transfer: deployment={} from={} to={} amount={} updateId={}",
                    deploymentId, fromParty, toParty, amount, updateId);

            eventPublisher.publishEvent(new CantonTransferEvent(deploymentId, actorId, actorRole,
                    Map.of("from", fromParty, "to", toParty, "amount", amount.toPlainString(), "updateId", updateId)));
            return updateId;
        });
    }

    // ── Forced transfer (issuer admin) ────────────────────────────────────────

    /**
     * Forced transfer exercised by the issuer (regulatory recovery / compliance action).
     */
    public CompletableFuture<String> forceTransfer(
            UUID deploymentId, String holdingContractId,
            String toParty, BigDecimal amount, String reason, UUID actorId, String actorRole) {

        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = loadDeployment(deploymentId);
            CantonLedgerClient client  = resolveClientForDeployment(deployment);
            CantonContext ctx           = resolveContext(deployment);

            Command cmd = new ExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE,
                            "Lfdt.Tokenstandard.Instrument",
                            "Instrument"),
                    deployment.getContractAddress(),
                    "ForceTransfer",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of(
                            field("holdingCid", new com.daml.ledger.javaapi.data.ContractId(holdingContractId)),
                            field("newOwner",   new com.daml.ledger.javaapi.data.Party(toParty)),
                            field("amount",     new com.daml.ledger.javaapi.data.Numeric(amount)),
                            field("reason",     new com.daml.ledger.javaapi.data.Text(reason))
                    )));

            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));
            log.info("Canton force-transfer: deployment={} to={} amount={} reason='{}' updateId={}",
                    deploymentId, toParty, amount, reason, updateId);

            eventPublisher.publishEvent(new CantonForceTransferEvent(deploymentId, actorId, actorRole,
                    Map.of("holdingContractId", holdingContractId, "to", toParty,
                            "amount", amount.toPlainString(), "reason", reason, "updateId", updateId)));
            return updateId;
        });
    }

    // ── Freeze / Unfreeze ─────────────────────────────────────────────────────

    /**
     * Freezes a holding (prevents the owner from transferring it).
     * Equivalent to Solana {@code freezeTokenAccount}.
     */
    public CompletableFuture<String> freezeHolding(UUID deploymentId, String holdingContractId,
                                                    UUID actorId, String actorRole) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = loadDeployment(deploymentId);
            CantonLedgerClient client  = resolveClientForDeployment(deployment);
            CantonContext ctx           = resolveContext(deployment);

            Command cmd = new ExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE, "Lfdt.Tokenstandard.Instrument", "Instrument"),
                    deployment.getContractAddress(), "LockHolding",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of(
                            field("holdingCid",
                                    new com.daml.ledger.javaapi.data.ContractId(holdingContractId)))));

            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));
            log.info("Canton holding locked: deployment={} holding={} updateId={}",
                    deploymentId, holdingContractId, updateId);

            eventPublisher.publishEvent(new CantonHoldingLockedEvent(deploymentId, actorId, actorRole,
                    Map.of("holdingContractId", holdingContractId, "updateId", updateId)));
            return updateId;
        });
    }

    /**
     * Unfreezes a previously frozen holding.
     * Equivalent to Solana {@code thawTokenAccount}.
     */
    public CompletableFuture<String> unfreezeHolding(UUID deploymentId, String holdingContractId,
                                                       UUID actorId, String actorRole) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = loadDeployment(deploymentId);
            CantonLedgerClient client  = resolveClientForDeployment(deployment);
            CantonContext ctx           = resolveContext(deployment);

            Command cmd = new ExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE, "Lfdt.Tokenstandard.Instrument", "Instrument"),
                    deployment.getContractAddress(), "UnlockHolding",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of(
                            field("holdingCid",
                                    new com.daml.ledger.javaapi.data.ContractId(holdingContractId)))));

            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));
            log.info("Canton holding unlocked: deployment={} holding={} updateId={}",
                    deploymentId, holdingContractId, updateId);

            eventPublisher.publishEvent(new CantonHoldingUnlockedEvent(deploymentId, actorId, actorRole,
                    Map.of("holdingContractId", holdingContractId, "updateId", updateId)));
            return updateId;
        });
    }

    // ── Burn ─────────────────────────────────────────────────────────────────

    /**
     * Burns tokens from a holding.
     */
    public CompletableFuture<String> burn(UUID deploymentId, String holdingContractId, BigDecimal amount,
                                           UUID actorId, String actorRole) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = loadDeployment(deploymentId);
            CantonLedgerClient client  = resolveClientForDeployment(deployment);
            CantonContext ctx           = resolveContext(deployment);

            Command cmd = new ExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE, "Lfdt.Tokenstandard.Instrument", "Instrument"),
                    deployment.getContractAddress(), "Burn",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of(
                            field("holdingCid",
                                    new com.daml.ledger.javaapi.data.ContractId(holdingContractId)),
                            field("amount", new com.daml.ledger.javaapi.data.Numeric(amount)))));

            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));
            log.info("Canton burn: deployment={} amount={} updateId={}", deploymentId, amount, updateId);

            eventPublisher.publishEvent(new CantonBurnEvent(deploymentId, actorId, actorRole,
                    Map.of("holdingContractId", holdingContractId, "amount", amount.toPlainString(), "updateId", updateId)));
            return updateId;
        });
    }

    // ── Pause / Unpause ───────────────────────────────────────────────────────

    /**
     * Pauses the instrument — all transfers on this instrument are blocked.
     */
    public CompletableFuture<String> pauseInstrument(UUID deploymentId, UUID actorId, String actorRole) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = loadDeployment(deploymentId);
            CantonLedgerClient client  = resolveClientForDeployment(deployment);
            CantonContext ctx           = resolveContext(deployment);

            Command cmd = new ExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE, "Lfdt.Tokenstandard.Instrument", "Instrument"),
                    deployment.getContractAddress(), "Pause",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of()));

            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));
            log.info("Canton instrument paused: deployment={} updateId={}", deploymentId, updateId);

            eventPublisher.publishEvent(new CantonInstrumentPausedEvent(deploymentId, actorId, actorRole,
                    Map.of("updateId", updateId)));
            return updateId;
        });
    }

    /**
     * Resumes a paused instrument.
     */
    public CompletableFuture<String> unpauseInstrument(UUID deploymentId, UUID actorId, String actorRole) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment deployment = loadDeployment(deploymentId);
            CantonLedgerClient client  = resolveClientForDeployment(deployment);
            CantonContext ctx           = resolveContext(deployment);

            Command cmd = new ExerciseCommand(
                    new com.daml.ledger.javaapi.data.Identifier(
                            TOKEN_STANDARD_PACKAGE, "Lfdt.Tokenstandard.Instrument", "Instrument"),
                    deployment.getContractAddress(), "Resume",
                    new com.daml.ledger.javaapi.data.DamlRecord(List.of()));

            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));
            log.info("Canton instrument resumed: deployment={} updateId={}", deploymentId, updateId);

            eventPublisher.publishEvent(new CantonInstrumentResumedEvent(deploymentId, actorId, actorRole,
                    Map.of("updateId", updateId)));
            return updateId;
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CantonLedgerClient resolveClient(Network network) {
        String identifier = "CANTON_" + (network == Network.MAINNET ? "MAINNET" : "DEVNET");
        return (CantonLedgerClient) registry.getCantonClientByIdentifier(identifier);
    }

    private String resolveIdentifier(Network network) {
        return "CANTON_" + (network == Network.MAINNET ? "MAINNET" : "DEVNET");
    }

    private CantonLedgerClient resolveClientForDeployment(AssetDeployment deployment) {
        return resolveClient(deployment.getNetwork());
    }

    private CantonContext resolveContext(AssetDeployment deployment) {
        // Load the party ID + JWT from the chain_config-linked default wallet via WalletSigner —
        // the same default-wallet mechanism every other chain (EVM, Solana) uses.
        String identifier = resolveIdentifier(deployment.getNetwork());
        ChainConfig chainConfig = chainConfigRepository.findByIdentifier(identifier)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", "identifier", identifier));
        return walletSigner.cantonContextForChain(chainConfig.getId());
    }

    private AssetDeployment loadDeployment(UUID deploymentId) {
        return assetDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
    }

    private static com.daml.ledger.javaapi.data.DamlRecord.Field field(
            String name, com.daml.ledger.javaapi.data.Value value) {
        return new com.daml.ledger.javaapi.data.DamlRecord.Field(name, value);
    }
}
