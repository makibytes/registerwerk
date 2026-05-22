package de.makibytes.registerwerk.blockchain.api;

import com.daml.ledger.javaapi.data.Command;
import com.daml.ledger.javaapi.data.CreateCommand;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.ExerciseCommand;
import com.daml.ledger.javaapi.data.Identifier;
import com.daml.ledger.javaapi.data.Numeric;
import com.daml.ledger.javaapi.data.Party;
import com.daml.ledger.javaapi.data.Text;
import com.daml.ledger.javaapi.data.Value;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.blockchain.events.CantonBondCalledEvent;
import de.makibytes.registerwerk.blockchain.events.CantonBondCreatedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonBondRedeemedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonCouponPaidEvent;
import de.makibytes.registerwerk.blockchain.events.CantonFloatingRateFixedEvent;
import de.makibytes.registerwerk.chain.api.CantonLedgerClient;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles Canton bond instrument lifecycle operations using Registerwerk DAML templates
 * (Registerwerk.Bond.FixedRateBond, FloatingRateBond, ZeroCouponBond).
 *
 * <p>This class is <b>excluded from the default Maven compiler configuration</b> and only
 * compiled when the {@code canton} profile is active ({@code -Pcanton}).
 * Without that profile, {@link CantonBondDisabledStub} is registered instead.
 *
 * <p>DAML templates are defined in {@code daml/daml/Registerwerk/Bond/}. Run
 * {@code daml/build.sh} to regenerate Java bindings when templates change.
 */
@Service
public class CantonBondService implements CantonBondOperations {

    private static final Logger log = LoggerFactory.getLogger(CantonBondService.class);

    /** Module path of the Registerwerk bond templates in the DAML package. */
    private static final String BOND_MODULE = "Registerwerk.Bond";

    private final BlockchainClientRegistry registry;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AssetLookupPort assetLookupPort;
    private final AssetBondTermsRepository bondTermsRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CantonBondService(
            BlockchainClientRegistry registry,
            AssetDeploymentRepository assetDeploymentRepository,
            AssetLookupPort assetLookupPort,
            AssetBondTermsRepository bondTermsRepository,
            ApplicationEventPublisher eventPublisher) {
        this.registry                 = registry;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetRepository          = assetRepository;
        this.bondTermsRepository      = bondTermsRepository;
        this.eventPublisher            = eventPublisher;
    }

    // ── Bond instrument creation ──────────────────────────────────────────────

    @Override
    public CompletableFuture<String> createFixedBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Creating DAML FixedRateBond: assetId={} network={}", assetId, network);
            AssetBondTerms bt = requireBondTerms(assetId, terms);
            CantonLedgerClient client = resolveClient(network);

            DamlRecord createArgs = bondRecord(assetId, issuerPartyId,
                    f("couponRate", new Numeric(bt.getCouponRate() != null ? bt.getCouponRate() : BigDecimal.ZERO)));

            String updateId = client.submitAndWait(issuerPartyId, List.of(
                    new CreateCommand(id("FixedRateBond"), createArgs)));

            eventPublisher.publishEvent(new CantonBondCreatedEvent(null, null, "FIXED",
                    Map.of("assetId", assetId.toString(), "network", network.name(), "updateId", updateId)));
            log.info("FixedRateBond created: assetId={} updateId={}", assetId, updateId);
            return updateId;
        });
    }

    @Override
    public CompletableFuture<String> createFloatingBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Creating DAML FloatingRateBond: assetId={}", assetId);
            AssetBondTerms bt = requireBondTerms(assetId, terms);
            CantonLedgerClient client = resolveClient(network);

            DamlRecord createArgs = bondRecord(assetId, issuerPartyId,
                    f("referenceRate", new Text(bt.getReferenceRate() != null ? bt.getReferenceRate() : "")),
                    f("spread",        new Numeric(bt.getSpread() != null ? bt.getSpread() : BigDecimal.ZERO)),
                    f("latestFixedRate", com.daml.ledger.javaapi.data.DamlOptional.EMPTY));

            String updateId = client.submitAndWait(issuerPartyId, List.of(
                    new CreateCommand(id("FloatingRateBond"), createArgs)));

            eventPublisher.publishEvent(new CantonBondCreatedEvent(null, null, "FLOATING",
                    Map.of("assetId", assetId.toString(), "updateId", updateId)));
            return updateId;
        });
    }

    @Override
    public CompletableFuture<String> createZeroBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Creating DAML ZeroCouponBond: assetId={}", assetId);
            requireBondTerms(assetId, terms);
            CantonLedgerClient client = resolveClient(network);

            DamlRecord createArgs = bondRecord(assetId, issuerPartyId,
                    f("issuePrice", new Numeric(BigDecimal.ONE)));

            String updateId = client.submitAndWait(issuerPartyId, List.of(
                    new CreateCommand(id("ZeroCouponBond"), createArgs)));

            eventPublisher.publishEvent(new CantonBondCreatedEvent(null, null, "ZERO_COUPON",
                    Map.of("assetId", assetId.toString(), "updateId", updateId)));
            return updateId;
        });
    }

    // ── Lifecycle operations ──────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> payCoupon(
            UUID deploymentId, Instant paymentDate, BigDecimal amountPerUnit, UUID actorId) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment dep = loadDeployment(deploymentId);
            CantonLedgerClient client = resolveClient(dep.getNetwork());
            log.info("CantonBond PayCoupon: deploymentId={} paymentDate={}", deploymentId, paymentDate);

            DamlRecord args = rec(
                    f("paymentDate",   new Text(paymentDate.toString())),
                    f("amountPerUnit", new Numeric(amountPerUnit)),
                    f("txRef",         new Text(deploymentId + "-coupon-" + paymentDate.getEpochSecond())));

            Command cmd = new ExerciseCommand(bondTemplateId(dep), dep.getContractAddress(), "PayCoupon", args);
            String updateId = client.submitAndWait(client.registryPartyId(), List.of(cmd));

            LocalDate pd = LocalDate.ofInstant(paymentDate, ZoneOffset.UTC);
            eventPublisher.publishEvent(new CantonCouponPaidEvent(deploymentId, actorId, pd, amountPerUnit, updateId));
            return updateId;
        });
    }

    @Override
    public CompletableFuture<String> fixFloatingRate(
            UUID deploymentId, BigDecimal rate, Instant fixingDate, UUID actorId) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment dep = loadDeployment(deploymentId);
            CantonLedgerClient client = resolveClient(dep.getNetwork());

            DamlRecord args = rec(
                    f("fixingDate", new Text(fixingDate.toString())),
                    f("rate",       new Numeric(rate)));

            Command cmd = new ExerciseCommand(bondTemplateId(dep), dep.getContractAddress(), "FixRate", args);
            String updateId = client.submitAndWait(client.registryPartyId(), List.of(cmd));

            eventPublisher.publishEvent(new CantonFloatingRateFixedEvent(deploymentId, actorId, rate, fixingDate));
            return updateId;
        });
    }

    @Override
    public CompletableFuture<String> redeem(UUID deploymentId, Instant maturityDate, UUID actorId) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment dep = loadDeployment(deploymentId);
            CantonLedgerClient client = resolveClient(dep.getNetwork());

            Command cmd = new ExerciseCommand(bondTemplateId(dep), dep.getContractAddress(), "Redeem", rec());
            String updateId = client.submitAndWait(client.registryPartyId(), List.of(cmd));

            bondTermsRepository.findById(dep.getAssetId()).ifPresent(bt -> {
                bt.setBondStatus(BondStatus.REDEEMED);
                bondTermsRepository.save(bt);
            });
            eventPublisher.publishEvent(new CantonBondRedeemedEvent(deploymentId, actorId, maturityDate));
            return updateId;
        });
    }

    @Override
    public CompletableFuture<String> earlyCall(
            UUID deploymentId, Instant callDate, BigDecimal callPrice, UUID actorId) {
        return CompletableFuture.supplyAsync(() -> {
            AssetDeployment dep = loadDeployment(deploymentId);
            CantonLedgerClient client = resolveClient(dep.getNetwork());

            DamlRecord args = rec(
                    f("callDate",  new Text(callDate.toString())),
                    f("callPrice", new Numeric(callPrice)));

            Command cmd = new ExerciseCommand(bondTemplateId(dep), dep.getContractAddress(), "EarlyCall", args);
            String updateId = client.submitAndWait(client.registryPartyId(), List.of(cmd));

            bondTermsRepository.findById(dep.getAssetId()).ifPresent(bt -> {
                bt.setBondStatus(BondStatus.CALLED);
                bondTermsRepository.save(bt);
            });
            eventPublisher.publishEvent(new CantonBondCalledEvent(deploymentId, actorId, callDate, callPrice));
            return updateId;
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CantonLedgerClient resolveClient(Network network) {
        String identifier = "CANTON_" + (network == Network.MAINNET ? "MAINNET" : "DEVNET");
        return (CantonLedgerClient) registry.getCantonClientByIdentifier(identifier);
    }

    private AssetDeployment loadDeployment(UUID deploymentId) {
        return assetDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
    }

    private AssetBondTerms requireBondTerms(UUID assetId, BondCreationTerms override) {
        if (override != null) {
            AssetBondTerms bt = bondTermsRepository.findById(assetId).orElseGet(() -> {
                AssetBondTerms n = new AssetBondTerms();
                n.setAssetId(assetId);
                return n;
            });
            if (override.faceValue()    != null) bt.setFaceValue(override.faceValue());
            if (override.currencyIso()  != null) bt.setCurrencyIso(override.currencyIso());
            if (override.couponRate()   != null) bt.setCouponRate(override.couponRate());
            if (override.referenceRate()!= null) bt.setReferenceRate(override.referenceRate());
            if (override.spread()       != null) bt.setSpread(override.spread());
            return bondTermsRepository.save(bt);
        }
        return bondTermsRepository.findById(assetId)
                .orElseThrow(() -> new IllegalStateException(
                        "Bond terms not found for assetId=" + assetId +
                        ". Create them via POST /api/v1/assets/{id}/bond-terms before deploying."));
    }

    private Identifier bondTemplateId(AssetDeployment dep) {
        TokenStandard std = assetRepository.findById(dep.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", dep.getAssetId()))
                .getTokenStandard();
        String entity = switch (std) {
            case DAML_BOND_FIXED    -> "FixedRateBond";
            case DAML_BOND_FLOATING -> "FloatingRateBond";
            case DAML_BOND_ZERO     -> "ZeroCouponBond";
            default -> throw new IllegalArgumentException("Not a DAML bond deployment: " + dep.getId());
        };
        return id(entity);
    }

    private Identifier id(String entity) {
        return new Identifier(BOND_MODULE, entity, entity);
    }

    /** Builds a DamlRecord with the bond common fields plus any additional fields. */
    private DamlRecord bondRecord(UUID assetId, String issuerPartyId, DamlRecord.Field... extra) {
        List<DamlRecord.Field> fields = new java.util.ArrayList<>(List.of(
                f("assetId",           new Text(assetId.toString())),
                f("registryAdmin",     new Party(issuerPartyId)),
                f("issuer",            new Party(issuerPartyId)),
                f("regulatorObserver", new Party(issuerPartyId)),
                f("status",            new Text("ACTIVE"))
        ));
        fields.addAll(List.of(extra));
        return new DamlRecord(fields);
    }

    private static DamlRecord rec(DamlRecord.Field... fields) {
        return new DamlRecord(List.of(fields));
    }

    private static DamlRecord.Field f(String name, Value value) {
        return new DamlRecord.Field(name, value);
    }
}
