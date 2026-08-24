package de.makibytes.registerwerk.blockchain.api;

import com.daml.ledger.javaapi.data.Command;
import com.daml.ledger.javaapi.data.Bool;
import com.daml.ledger.javaapi.data.CreateCommand;
import com.daml.ledger.javaapi.data.DamlEnum;
import com.daml.ledger.javaapi.data.DamlList;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.Date;
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
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.blockchain.events.CantonBondCalledEvent;
import de.makibytes.registerwerk.blockchain.events.CantonBondCreatedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonBondRedeemedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonCouponPaidEvent;
import de.makibytes.registerwerk.blockchain.events.CantonFloatingRateFixedEvent;
import de.makibytes.registerwerk.chain.api.CantonLedgerClient;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.WalletStorage.CantonContext;
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
import java.util.function.Function;
import de.makibytes.registerwerk.finality.api.ChainSubmissionExecutor;

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
 *
 * <p>Commands use package-name addressing and encode the nested {@code EwpgBondTerms}, enum,
 * list, and date values exactly as declared by the DAML templates. Keeping this hand-written
 * encoder small is intentional; its output is covered by profile-specific unit tests and can
 * later be replaced mechanically by Daml Java code generation.
 */
@Service
public class CantonBondService implements CantonBondOperations {

    private static final Logger log = LoggerFactory.getLogger(CantonBondService.class);

    /** Ledger API 3.4+ package-name reference (package hashes are no longer accepted). */
    private static final String BOND_PACKAGE = "#registerwerk-canton";

    private final BlockchainClientRegistry registry;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final AssetLookupPort assetLookupPort;
    private final AssetBondTermsRepository bondTermsRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ChainConfigRepository chainConfigRepository;
    private final WalletSigner walletSigner;
    private final ChainSubmissionExecutor submissions;

    public CantonBondService(
            BlockchainClientRegistry registry,
            AssetDeploymentRepository assetDeploymentRepository,
            AssetLookupPort assetLookupPort,
            AssetBondTermsRepository bondTermsRepository,
            ApplicationEventPublisher eventPublisher,
            ChainConfigRepository chainConfigRepository,
            WalletSigner walletSigner,
            ChainSubmissionExecutor submissions) {
        this.registry                 = registry;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.assetLookupPort          = assetLookupPort;
        this.bondTermsRepository      = bondTermsRepository;
        this.eventPublisher            = eventPublisher;
        this.chainConfigRepository    = chainConfigRepository;
        this.walletSigner             = walletSigner;
        this.submissions              = submissions;
    }

    // ── Bond instrument creation ──────────────────────────────────────────────

    @Override
    public CompletableFuture<TokenDeploymentResult> createFixedBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms,
            UUID actorId, String actorRole) {
        return submitOnNetwork(network, chain -> {
            log.info("Creating DAML FixedRateBond: assetId={} network={}", assetId, network);
            AssetBondTerms bt = requireBondTerms(assetId, terms);
            CantonLedgerClient client = resolveClient(network);

            DamlRecord createArgs = bondRecord(assetId, issuerPartyId, bt,
                    f("couponRate", new Numeric(bt.getCouponRate() != null ? bt.getCouponRate() : BigDecimal.ZERO)));

            Identifier template = id("FixedRateBond");
            CantonLedgerClient.CommittedContract committed = client.submitAndWaitForCreatedContract(
                    issuerPartyId, List.of(new CreateCommand(template, createArgs)), template);
            String updateId = committed.updateId();

            eventPublisher.publishEvent(new CantonBondCreatedEvent(null, actorId, actorRole, "FIXED",
                    Map.of("assetId", assetId.toString(), "network", network.name(), "updateId", updateId)));
            log.info("FixedRateBond created: assetId={} updateId={}", assetId, updateId);
            return new TokenDeploymentResult(updateId, committed.contractId());
        });
    }

    @Override
    public CompletableFuture<TokenDeploymentResult> createFloatingBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms,
            UUID actorId, String actorRole) {
        return submitOnNetwork(network, chain -> {
            log.info("Creating DAML FloatingRateBond: assetId={}", assetId);
            AssetBondTerms bt = requireBondTerms(assetId, terms);
            CantonLedgerClient client = resolveClient(network);

            DamlRecord createArgs = bondRecord(assetId, issuerPartyId, bt,
                    f("referenceRate", new Text(bt.getReferenceRate() != null ? bt.getReferenceRate() : "")),
                    f("spread",        new Numeric(bt.getSpread() != null ? bt.getSpread() : BigDecimal.ZERO)),
                    f("latestFixedRate", com.daml.ledger.javaapi.data.DamlOptional.EMPTY));

            Identifier template = id("FloatingRateBond");
            CantonLedgerClient.CommittedContract committed = client.submitAndWaitForCreatedContract(
                    issuerPartyId, List.of(new CreateCommand(template, createArgs)), template);
            String updateId = committed.updateId();

            eventPublisher.publishEvent(new CantonBondCreatedEvent(null, actorId, actorRole, "FLOATING",
                    Map.of("assetId", assetId.toString(), "updateId", updateId)));
            return new TokenDeploymentResult(updateId, committed.contractId());
        });
    }

    @Override
    public CompletableFuture<TokenDeploymentResult> createZeroBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms,
            UUID actorId, String actorRole) {
        return submitOnNetwork(network, chain -> {
            log.info("Creating DAML ZeroCouponBond: assetId={}", assetId);
            AssetBondTerms bt = requireBondTerms(assetId, terms);
            CantonLedgerClient client = resolveClient(network);

            // Falls back to BigDecimal.ONE (100% of face value) only when issuePrice is unset —
            // a genuine discount-priced zero-coupon bond, the entire point of the instrument,
            // must be recorded at its real issue price.
            DamlRecord createArgs = bondRecord(assetId, issuerPartyId, bt,
                    f("issuePrice", new Numeric(bt.getIssuePrice() != null ? bt.getIssuePrice() : BigDecimal.ONE)));

            Identifier template = id("ZeroCouponBond");
            CantonLedgerClient.CommittedContract committed = client.submitAndWaitForCreatedContract(
                    issuerPartyId, List.of(new CreateCommand(template, createArgs)), template);
            String updateId = committed.updateId();

            eventPublisher.publishEvent(new CantonBondCreatedEvent(null, actorId, actorRole, "ZERO_COUPON",
                    Map.of("assetId", assetId.toString(), "updateId", updateId)));
            return new TokenDeploymentResult(updateId, committed.contractId());
        });
    }

    // ── Lifecycle operations ──────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> payCoupon(
            UUID deploymentId, Instant paymentDate, BigDecimal amountPerUnit, UUID actorId) {
        return submitOnDeployment(deploymentId, dep -> {
            CantonLedgerClient client = resolveClient(dep.getNetwork());
            CantonContext ctx = resolveContext(dep);
            log.info("CantonBond PayCoupon: deploymentId={} paymentDate={}", deploymentId, paymentDate);

            DamlRecord args = rec(
                    f("paymentDate",   date(paymentDate)),
                    f("amountPerUnit", new Numeric(amountPerUnit)),
                    f("txRef",         new Text(deploymentId + "-coupon-" + paymentDate.getEpochSecond())));

            Command cmd = new ExerciseCommand(bondTemplateId(dep), dep.getContractAddress(), "PayCoupon", args);
            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));

            LocalDate pd = LocalDate.ofInstant(paymentDate, ZoneOffset.UTC);
            eventPublisher.publishEvent(new CantonCouponPaidEvent(deploymentId, actorId, pd, amountPerUnit, updateId));
            return updateId;
        });
    }

    @Override
    public CompletableFuture<String> fixFloatingRate(
            UUID deploymentId, BigDecimal rate, Instant fixingDate, UUID actorId) {
        return submitOnDeployment(deploymentId, dep -> {
            CantonLedgerClient client = resolveClient(dep.getNetwork());
            CantonContext ctx = resolveContext(dep);

            DamlRecord args = rec(
                    f("fixingDate", date(fixingDate)),
                    f("rate",       new Numeric(rate)));

            Command cmd = new ExerciseCommand(bondTemplateId(dep), dep.getContractAddress(), "FixRate", args);
            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));

            eventPublisher.publishEvent(new CantonFloatingRateFixedEvent(deploymentId, actorId, rate, fixingDate));
            return updateId;
        });
    }

    @Override
    public CompletableFuture<String> redeem(UUID deploymentId, Instant maturityDate, UUID actorId) {
        return submitOnDeployment(deploymentId, dep -> {
            CantonLedgerClient client = resolveClient(dep.getNetwork());
            CantonContext ctx = resolveContext(dep);

            DamlRecord args = rec(f("redemptionDate", date(maturityDate)));
            Command cmd = new ExerciseCommand(bondTemplateId(dep), dep.getContractAddress(), "Redeem", args);
            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));

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
        return submitOnDeployment(deploymentId, dep -> {
            CantonLedgerClient client = resolveClient(dep.getNetwork());
            CantonContext ctx = resolveContext(dep);

            DamlRecord args = rec(
                    f("callDate",  date(callDate)),
                    f("callPrice", new Numeric(callPrice)));

            Command cmd = new ExerciseCommand(bondTemplateId(dep), dep.getContractAddress(), "EarlyCall", args);
            String updateId = client.submitAndWait(ctx.partyId(), List.of(cmd));

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

    /** Resolves the acting party for lifecycle-choice exercises — this
     *  previously called a {@code client.registryPartyId()} method that doesn't exist on {@link
     *  CantonLedgerClient}/{@code CantonLedgerEndpoint} (a genuine compile break, invisible to
     *  default CI since this class only compiles under {@code -Pcanton}). Mirrors {@code
     *  CantonTokenService.resolveContext}'s existing pattern exactly: the party ID + JWT come
     *  from the chain_config-linked default wallet via {@link WalletSigner}, the same
     *  default-wallet mechanism every other chain (EVM, Solana) uses. */
    private CantonContext resolveContext(AssetDeployment dep) {
        String identifier = "CANTON_" + (dep.getNetwork() == Network.MAINNET ? "MAINNET" : "DEVNET");
        ChainConfig chainConfig = chainConfigRepository.findByIdentifier(identifier)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", "identifier", identifier));
        return walletSigner.cantonContextForChain(chainConfig.getId());
    }

    private AssetDeployment loadDeployment(UUID deploymentId) {
        return assetDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
    }

    private <T> CompletableFuture<T> submitOnNetwork(
            Network network, Function<ChainConfig, T> submission) {
        ChainConfig chain = requireChainConfig(network);
        return CompletableFuture.supplyAsync(() -> submissions.execute(
                chain.getId(), () -> submission.apply(chain)));
    }

    private <T> CompletableFuture<T> submitOnDeployment(
            UUID deploymentId, Function<AssetDeployment, T> submission) {
        AssetDeployment deployment = loadDeployment(deploymentId);
        ChainConfig chain = requireDeploymentChain(deployment);
        return CompletableFuture.supplyAsync(() -> submissions.execute(
                chain.getId(), () -> submission.apply(deployment)));
    }

    private ChainConfig requireChainConfig(Network network) {
        String identifier = "CANTON_" + (network == Network.MAINNET ? "MAINNET" : "DEVNET");
        return chainConfigRepository.findByIdentifier(identifier)
                .filter(ChainConfig::isEnabled)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Enabled ChainConfig", "identifier", identifier));
    }

    private ChainConfig requireDeploymentChain(AssetDeployment deployment) {
        if (deployment.getChainConfigId() == null) {
            throw new IllegalStateException(
                    "Canton deployment is missing chainConfigId: " + deployment.getId());
        }
        return chainConfigRepository.findById(deployment.getChainConfigId())
                .filter(ChainConfig::isEnabled)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Enabled ChainConfig", deployment.getChainConfigId()));
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
            if (override.issueDate()    != null) bt.setIssueDate(LocalDate.parse(override.issueDate()));
            if (override.maturityDate() != null) bt.setMaturityDate(LocalDate.parse(override.maturityDate()));
            if (override.couponRate()   != null) bt.setCouponRate(override.couponRate());
            if (override.referenceRate()!= null) bt.setReferenceRate(override.referenceRate());
            if (override.spread()       != null) bt.setSpread(override.spread());
            if (override.dayCount()     != null) {
                bt.setDayCount(de.makibytes.registerwerk.deployment.api.DayCountConvention
                        .valueOf(override.dayCount()));
            }
            if (override.paymentFrequency() != null) {
                bt.setPaymentFrequency(de.makibytes.registerwerk.deployment.api.PaymentFrequency
                        .valueOf(override.paymentFrequency()));
            }
            bt.setCallable(override.callable());
            if (override.issuePrice()   != null) bt.setIssuePrice(override.issuePrice());
            return bondTermsRepository.save(bt);
        }
        return bondTermsRepository.findById(assetId)
                .orElseThrow(() -> new IllegalStateException(
                        "Bond terms not found for assetId=" + assetId +
                        ". Create them via POST /api/v1/assets/{id}/bond-terms before deploying."));
    }

    private Identifier bondTemplateId(AssetDeployment dep) {
        TokenStandard std = assetLookupPort.findById(dep.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", dep.getAssetId()))
                .tokenStandard();
        String entity = switch (std) {
            case DAML_BOND_FIXED    -> "FixedRateBond";
            case DAML_BOND_FLOATING -> "FloatingRateBond";
            case DAML_BOND_ZERO     -> "ZeroCouponBond";
            default -> throw new IllegalArgumentException("Not a DAML bond deployment: " + dep.getId());
        };
        return id(entity);
    }

    static Identifier id(String entity) {
        String module = switch (entity) {
            case "FixedRateBond" -> "Registerwerk.Bond.FixedBond";
            case "FloatingRateBond" -> "Registerwerk.Bond.FloatingBond";
            case "ZeroCouponBond" -> "Registerwerk.Bond.ZeroBond";
            default -> throw new IllegalArgumentException("Unknown Registerwerk bond template: " + entity);
        };
        return new Identifier(BOND_PACKAGE, module, entity);
    }

    /** Builds a DamlRecord with the bond common fields plus any additional fields. */
    static DamlRecord bondRecord(
            UUID assetId,
            String issuerPartyId,
            AssetBondTerms terms,
            DamlRecord.Field... extra) {
        List<DamlRecord.Field> fields = new java.util.ArrayList<>(List.of(
                f("assetId",           new Text(assetId.toString())),
                f("registryAdmin",     new Party(issuerPartyId)),
                f("issuer",            new Party(issuerPartyId)),
                f("regulatorObserver", new Party(issuerPartyId)),
                f("terms",             bondTermsRecord(terms))
        ));
        fields.addAll(List.of(extra));
        fields.add(f("status", new Text("ACTIVE")));
        return new DamlRecord(fields);
    }

    private static DamlRecord bondTermsRecord(AssetBondTerms terms) {
        requireCompleteTerms(terms);
        List<Value> callEntries = terms.getCallSchedule() == null
                ? List.of()
                : terms.getCallSchedule().stream()
                        .<Value>map(CantonBondService::callEntryRecord)
                        .toList();
        return rec(
                f("assetId", new Text(terms.getAssetId().toString())),
                f("faceValue", new Numeric(terms.getFaceValue())),
                f("currencyIso", new Text(terms.getCurrencyIso())),
                f("issueDate", date(terms.getIssueDate())),
                f("maturityDate", date(terms.getMaturityDate())),
                f("dayCount", new DamlEnum(dayCountConstructor(terms.getDayCount()))),
                f("paymentFrequency", new DamlEnum(paymentFrequencyConstructor(terms.getPaymentFrequency()))),
                f("callable", Bool.of(terms.isCallable())),
                f("callSchedule", DamlList.of(callEntries)));
    }

    private static DamlRecord callEntryRecord(Map<String, Object> entry) {
        Object rawDate = entry.get("callDate");
        Object rawPrice = entry.get("callPrice");
        if (rawDate == null || rawPrice == null) {
            throw new IllegalStateException("Canton bond call schedule requires callDate and callPrice");
        }
        LocalDate callDate = rawDate instanceof LocalDate localDate
                ? localDate : LocalDate.parse(rawDate.toString());
        BigDecimal callPrice = rawPrice instanceof BigDecimal decimal
                ? decimal : new BigDecimal(rawPrice.toString());
        return rec(f("callDate", date(callDate)), f("callPrice", new Numeric(callPrice)));
    }

    private static void requireCompleteTerms(AssetBondTerms terms) {
        if (terms.getAssetId() == null || terms.getFaceValue() == null
                || terms.getCurrencyIso() == null || terms.getIssueDate() == null
                || terms.getMaturityDate() == null || terms.getDayCount() == null
                || terms.getPaymentFrequency() == null) {
            throw new IllegalStateException(
                    "Canton bond deployment requires complete face value, currency, dates, day-count, and payment-frequency terms");
        }
        if (!terms.getMaturityDate().isAfter(terms.getIssueDate())) {
            throw new IllegalStateException("Canton bond maturityDate must be after issueDate");
        }
        if (!terms.isCallable() && terms.getCallSchedule() != null && !terms.getCallSchedule().isEmpty()) {
            throw new IllegalStateException("A non-callable Canton bond cannot have a call schedule");
        }
    }

    private static String dayCountConstructor(
            de.makibytes.registerwerk.deployment.api.DayCountConvention dayCount) {
        return switch (dayCount) {
            case ACT_360 -> "Act360";
            case ACT_365 -> "Act365";
            case ACT_ACT_ICMA -> "ActActIcma";
            case THIRTY_360 -> "Thirty360";
            case THIRTY_E_360 -> "ThirtyE360";
        };
    }

    private static String paymentFrequencyConstructor(
            de.makibytes.registerwerk.deployment.api.PaymentFrequency frequency) {
        return switch (frequency) {
            case ANNUAL -> "Annual";
            case SEMI_ANNUAL -> "SemiAnnual";
            case QUARTERLY -> "Quarterly";
            case MONTHLY -> "Monthly";
            case ZERO -> "Zero";
        };
    }

    private static Date date(Instant instant) {
        return date(LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Date date(LocalDate localDate) {
        return new Date(Math.toIntExact(localDate.toEpochDay()));
    }

    private static DamlRecord rec(DamlRecord.Field... fields) {
        return new DamlRecord(List.of(fields));
    }

    private static DamlRecord.Field f(String name, Value value) {
        return new DamlRecord.Field(name, value);
    }
}
