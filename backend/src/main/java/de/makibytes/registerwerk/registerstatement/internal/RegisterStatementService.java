package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDocument;
import de.makibytes.registerwerk.asset.api.AssetDocumentRepository;
import de.makibytes.registerwerk.asset.api.AssetDocumentType;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.RegisterFreezeGuard;
import de.makibytes.registerwerk.asset.api.RegisterReconciliationGuard;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.api.JurisdictionRequirementConfig;
import de.makibytes.registerwerk.kyc.api.RegisterDocumentProfile;
import de.makibytes.registerwerk.notification.api.EmailPort;
import de.makibytes.registerwerk.registerstatement.api.DeliveryStatus;
import de.makibytes.registerwerk.registerstatement.api.RegisterStatement;
import de.makibytes.registerwerk.registerstatement.api.RegisterStatementRepository;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import de.makibytes.registerwerk.registerstatement.events.RegisterStatementDeliveryFailedEvent;
import de.makibytes.registerwerk.registerstatement.events.RegisterStatementIssuedEvent;
import de.makibytes.registerwerk.shared.DocumentSigningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues §19 eWpG register statements (Registerauszüge) to consumer holders of
 * single-entry crypto securities.
 *
 * <p>The statement obligation (§19(2) eWpG) is owed only when ALL of the
 * following hold, so the service is the single gate that decides eligibility:
 * <ul>
 *   <li>the asset is in single entry (Einzeleintragung) — in a collective entry
 *       the holder of record is a custodian, not the investor;</li>
 *   <li>the specific holder is flagged as a consumer (Verbraucher).</li>
 * </ul>
 * For an on-demand request (§19(1)) the consumer restriction does not apply —
 * any holder may obtain a statement where needed to exercise their rights — but
 * single entry is still required, since collective-entry investors are not in
 * the register by name.
 *
 * <p>Each issued statement is persisted as a register record with the SHA-256
 * hash of the rendered PDF (so later tampering is detectable) and a delivery
 * status. Delivery failures are recorded, not thrown: a failed e-mail must not
 * roll back the statement record, and the retry worker re-attempts later.
 */
@Service
public class RegisterStatementService {

    private static final Logger log = LoggerFactory.getLogger(RegisterStatementService.class);

    private final RegisterStatementRepository statementRepository;
    private final AssetHolderRepository holderRepository;
    private final AssetRepository assetRepository;
    private final LegalEntityRepository entityRepository;
    private final AppUserRepository userRepository;
    private final HolderBlockRepository blockRepository;
    private final AssetBondTermsRepository bondTermsRepository;
    private final AssetDocumentRepository documentRepository;
    private final JurisdictionRequirementConfig jurisdictionConfig;
    private final EmailPort emailService;
    private final DocumentSigningService signingService;
    private final ApplicationEventPublisher eventPublisher;
    private final FinalityGate finalityGate;
    private final String registryName;
    private final String registryCountry;

    public RegisterStatementService(
            RegisterStatementRepository statementRepository,
            AssetHolderRepository holderRepository,
            AssetRepository assetRepository,
            LegalEntityRepository entityRepository,
            AppUserRepository userRepository,
            HolderBlockRepository blockRepository,
            AssetBondTermsRepository bondTermsRepository,
            AssetDocumentRepository documentRepository,
            JurisdictionRequirementConfig jurisdictionConfig,
            EmailPort emailService,
            DocumentSigningService signingService,
            ApplicationEventPublisher eventPublisher,
            FinalityGate finalityGate,
            @Value("${registerwerk.registry.name:Registerwerk eWpG-Registry}") String registryName,
            @Value("${registerwerk.registry.country:DE}") String registryCountry) {
        this.statementRepository = statementRepository;
        this.holderRepository = holderRepository;
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
        this.userRepository = userRepository;
        this.blockRepository = blockRepository;
        this.bondTermsRepository = bondTermsRepository;
        this.documentRepository = documentRepository;
        this.jurisdictionConfig = jurisdictionConfig;
        this.emailService = emailService;
        this.signingService = signingService;
        this.eventPublisher = eventPublisher;
        this.finalityGate = finalityGate;
        this.registryName = registryName;
        this.registryCountry = registryCountry;
    }

    /**
     * Everything the renderer and the content hash need beyond the holder/asset/investor
     * triple, gathered once per render so {@link #issueForHolder}, {@link #retryFailedDeliveries}
     * and {@link #renderForDownload} don't each re-query it independently.
     */
    private record DocumentContext(LegalEntity issuer, List<HolderBlock> blocks, AssetBondTerms bondTerms,
                                   AssetDocument termSheet, RegisterDocumentProfile profile) {}

    private DocumentContext buildContext(Asset asset, AssetHolder holder) {
        LegalEntity issuer = entityRepository.findById(asset.getIssuerId()).orElse(null);
        List<HolderBlock> blocks = blocksFor(asset, holder);
        AssetBondTerms bondTerms = bondTermsRepository.findById(asset.getId()).orElse(null);
        AssetDocument termSheet = documentRepository
                .findByAssetIdAndDocumentTypeAndDeletedAtIsNull(asset.getId(), AssetDocumentType.TERM_SHEET)
                .stream().findFirst().orElse(null);
        boolean individualEntry = holder.getEntryType() == EntryType.INDIVIDUAL;
        RegisterDocumentProfile profile = jurisdictionConfig
                .resolveRegisterDocumentProfile(asset.getJurisdiction(), individualEntry);
        return new DocumentContext(issuer, blocks, bondTerms, termSheet, profile);
    }

    /**
     * Every enforced Sperrvermerk that applies to this holding (9A-05): blocks on the holder's wallet AND entity-level
     * blocks on the investor (a block placed through another of the entity's wallets still restricts this holding),
     * limited to blocks that cover this asset ({@code assetId} null = all assets) and de-duplicated. Same predicate as
     * {@code RegisterTransferService.blockSnapshots}. The content hash includes these rows.
     */
    private List<HolderBlock> blocksFor(Asset asset, AssetHolder holder) {
        java.util.Map<Object, HolderBlock> byIdentity = new java.util.LinkedHashMap<>();
        List<HolderBlock> found = new java.util.ArrayList<>(
                blockRepository.findByWalletAddressAndStatusIn(holder.getWalletAddress(), HolderBlock.BLOCKING));
        if (holder.getInvestorId() != null) {
            found.addAll(blockRepository.findByEntityIdAndStatusIn(holder.getInvestorId(), HolderBlock.BLOCKING));
        }
        for (HolderBlock b : found) {
            if (b.getAssetId() == null || b.getAssetId().equals(asset.getId())) {
                byIdentity.putIfAbsent(b.getId() != null ? b.getId() : b, b);
            }
        }
        return List.copyOf(byIdentity.values());
    }

    private byte[] renderStatement(Asset asset, AssetHolder holder, LegalEntity investor,
                                   StatementTrigger trigger, LocalDate issuedDate, DocumentContext ctx) {
        byte[] unsigned = RegisterStatementPdfRenderer.render(
                asset, holder, investor, ctx.issuer(), ctx.blocks(), ctx.bondTerms(), ctx.termSheet(),
                trigger, ctx.profile(), registryName, registryCountry, issuedDate);
        return signingService.isConfigured()
                ? signingService.signPdf(unsigned, "Registerauszug — " + holder.getHolderReference())
                : unsigned;
    }

    /**
     * Self-service on-demand download for the holder's own holding (§19(1) for
     * individual entries; a jurisdiction-labeled holding confirmation for collective /
     * nominee entries — never the same document). Unlike {@link #issueForHolder}, both
     * entry types are eligible: the document rendered simply differs by type. Each
     * download is logged as an ON_DEMAND issuance (so the register can later show what
     * was disclosed and when), but same-day repeat downloads are deduped rather than
     * spamming the register with identical rows.
     */
    @Transactional
    public Optional<byte[]> renderForDownload(UUID holderId) {
        AssetHolder holder = holderRepository.findById(holderId).orElse(null);
        if (holder == null) {
            return Optional.empty();
        }
        Asset asset = assetRepository.findById(holder.getAssetId()).orElse(null);
        LegalEntity investor = entityRepository.findById(holder.getInvestorId()).orElse(null);
        if (asset == null || investor == null) {
            return Optional.empty();
        }
        RegisterFreezeGuard.requireAdministeredHere(asset, "Register document download");
        // 9A-05 (interim T9-01): a BLOCKED holder sync wrote no holder row, so every nominal may be stale.
        RegisterReconciliationGuard.requireReconciled(asset, "Register document download");

        Instant issuedAt = Instant.now();
        LocalDate issuedDate = issuedAt.atZone(ZoneOffset.UTC).toLocalDate();
        DocumentContext ctx = buildContext(asset, holder);
        byte[] pdf = renderStatement(asset, holder, investor, StatementTrigger.ON_DEMAND, issuedDate, ctx);

        boolean alreadyIssuedToday = statementRepository.findByHolderIdOrderByIssuedAtDesc(holderId).stream()
                .filter(s -> s.getTrigger() == StatementTrigger.ON_DEMAND)
                .anyMatch(s -> s.getIssuedAt().atZone(ZoneOffset.UTC).toLocalDate().equals(issuedDate));
        if (!alreadyIssuedToday) {
            RegisterStatement statement = new RegisterStatement();
            statement.setHolderId(holder.getId());
            statement.setAssetId(holder.getAssetId());
            statement.setInvestorId(holder.getInvestorId());
            statement.setTrigger(StatementTrigger.ON_DEMAND);
            statement.setNominalAmount(holder.getNominalAmount());
            statement.setWalletAddress(holder.getWalletAddress());
            statement.setHolderReference(holder.getHolderReference());
            statement.setIssuedAt(issuedAt);
            statement.setContentHash(sha256Hex(
                    contentKey(asset, holder, investor, StatementTrigger.ON_DEMAND, registryName, issuedDate, ctx)));
            statement.setDeliveryStatus(DeliveryStatus.DELIVERED);
            statement.setDeliveryChannel("DOWNLOAD");
            statement.setDeliveredAt(issuedAt);
            statement = statementRepository.save(statement);

            holder.setLastStatementAt(issuedAt);
            holderRepository.save(holder);

            eventPublisher.publishEvent(new RegisterStatementIssuedEvent(
                    statement.getId(), holder.getId(), holder.getAssetId(), holder.getInvestorId(), StatementTrigger.ON_DEMAND));
        }
        return Optional.of(pdf);
    }

    /**
     * Issues a statement for the given holder if the §19 conditions are met.
     * Returns empty (and does nothing) when the holder is not eligible — callers
     * on the event path can invoke this unconditionally.
     */
    @Transactional
    public Optional<RegisterStatement> issueForHolder(UUID holderId, StatementTrigger trigger) {
        AssetHolder holder = holderRepository.findById(holderId).orElse(null);
        if (holder == null) {
            log.warn("Register statement requested for unknown holder {}", holderId);
            return Optional.empty();
        }
        if (!isEligible(holder, trigger)) {
            return Optional.empty();
        }

        Asset asset = assetRepository.findById(holder.getAssetId()).orElse(null);
        LegalEntity investor = entityRepository.findById(holder.getInvestorId()).orElse(null);
        if (asset == null || investor == null) {
            log.warn("Register statement skipped: missing asset/investor for holder {}", holderId);
            return Optional.empty();
        }
        if (asset.getStatus() != null && !asset.getStatus().isAdministeredHere()) {
            // T3-07: the register was handed to a successor registrar - it issues the statements now.
            log.info("Register statement skipped: asset {} was transferred out", asset.getId());
            return Optional.empty();
        }
        // 9A-05 (interim T9-01): never issue/e-mail a statement from an unreconciled register (it cannot be un-sent).
        // lastStatementAt stays untouched, so the daily annual job issues it once the register is reconciled again.
        RegisterReconciliationGuard.requireReconciled(asset, "Register statement");

        // The first real FinalityGate call site (P8 — closes the "irreversible hole" the plan
        // flagged: a §19 statement PDF, once emailed, cannot be un-sent, matching this operation's
        // hard floor at FINALIZED). Only chain-derived balances are gated at all — an off-chain
        // register entry (indexer.api.HolderDataService's javadoc: chainDerived == false) has no
        // finality concept to check. currentLevel is passed as FINALIZED, not looked up, because
        // that is not a guess: HolderDataService.syncHoldersFromBlockchain only ever counts
        // FINALIZED transfers, so any chain-derived nominalAmount was computed from FINALIZED data
        // by construction. This makes today's check a no-op in practice — the real point is
        // establishing the correct enforcement point now, so it starts protecting immediately if
        // that invariant ever changes (e.g. a future policy-driven read filter, see
        // HolderDataService's own disclosed P5 deferral), without any further wiring here.
        if (holder.isChainDerived()) {
            finalityGate.require(GatedOperation.REGISTER_STATEMENT_ISSUE, holder.getAssetId(), asset.getTokenStandard(),
                    FinalityLevel.FINALIZED);
        }

        Instant issuedAt = Instant.now();
        LocalDate issuedDate = issuedAt.atZone(ZoneOffset.UTC).toLocalDate();
        DocumentContext ctx = buildContext(asset, holder);
        byte[] pdf = renderStatement(asset, holder, investor, trigger, issuedDate, ctx);

        RegisterStatement statement = new RegisterStatement();
        statement.setHolderId(holder.getId());
        statement.setAssetId(holder.getAssetId());
        statement.setInvestorId(holder.getInvestorId());
        statement.setTrigger(trigger);
        statement.setNominalAmount(holder.getNominalAmount());
        statement.setWalletAddress(holder.getWalletAddress());
        statement.setHolderReference(holder.getHolderReference());
        statement.setIssuedAt(issuedAt);
        // Hash the register inputs, not the PDF bytes: PDFBox embeds a random file
        // identifier on each save(), making PDF bytes non-deterministic. Hashing the
        // canonical input fields produces a stable, independently verifiable digest of
        // the register content disclosed to the holder at issuance.
        statement.setContentHash(sha256Hex(contentKey(asset, holder, investor, trigger, registryName, issuedDate, ctx)));
        statement.setDeliveryStatus(DeliveryStatus.PENDING);
        statement = statementRepository.save(statement);

        // Update the holder's annual-statement clock regardless of delivery outcome:
        // the obligation is to issue, and the record now exists.
        holder.setLastStatementAt(issuedAt);
        holderRepository.save(holder);

        eventPublisher.publishEvent(new RegisterStatementIssuedEvent(
                statement.getId(), holder.getId(), holder.getAssetId(), holder.getInvestorId(), trigger));

        deliver(statement, asset, investor, pdf);
        return Optional.of(statement);
    }

    /**
     * §19(1) on-demand statement requested by/for a holder. Bypasses the consumer
     * flag but still requires single entry.
     */
    @Transactional
    public Optional<RegisterStatement> issueOnDemand(UUID holderId) {
        return issueForHolder(holderId, StatementTrigger.ON_DEMAND);
    }

    private boolean isEligible(AssetHolder holder, StatementTrigger trigger) {
        boolean singleEntry = holder.getEntryType() == EntryType.INDIVIDUAL;
        if (!singleEntry) {
            return false;
        }
        // §19(2) periodic/event statements are owed to consumers; §19(1) on-demand
        // is available to any single-entry holder.
        if (trigger == StatementTrigger.ON_DEMAND) {
            return true;
        }
        return Boolean.TRUE.equals(holder.getIsConsumer());
    }

    /** Delivery failure class for holders whose users were erased/disabled (never retried). */
    static final String NO_LAWFUL_CHANNEL = "NO_LAWFUL_CHANNEL";

    private void deliver(RegisterStatement statement, Asset asset, LegalEntity investor, byte[] pdf) {
        List<AppUser> users = userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(investor.getId());
        String email = resolveEmail(users);
        if (email == null) {
            boolean erased = users.stream().anyMatch(u -> !u.isEnabled() || isErasedEmail(u.getEmail()));
            statement.setDeliveryStatus(DeliveryStatus.FAILED);
            if (erased) {
                statement.setDeliveryErrorCode(NO_LAWFUL_CHANNEL);
                statement.setDeliveryError("No lawful delivery channel (erasure): all users of investor "
                        + investor.getEntityNumber() + " are erased or disabled");
                statementRepository.save(statement);
                log.error("Statement {} undeliverable: no lawful delivery channel (erasure) for investor {} - "
                        + "operator action required", statement.getId(), investor.getEntityNumber());
                eventPublisher.publishEvent(new RegisterStatementDeliveryFailedEvent(statement.getId(),
                        statement.getHolderId(), statement.getAssetId(), statement.getInvestorId(), NO_LAWFUL_CHANNEL));
                return;
            }
            statement.setDeliveryError("No deliverable e-mail address for investor "
                    + investor.getEntityNumber());
            statementRepository.save(statement);
            log.warn("Statement {} undeliverable: no e-mail for investor {}",
                    statement.getId(), investor.getEntityNumber());
            return;
        }

        String subject = "Registerauszug / Register statement — " + asset.getName();
        boolean sent = emailService.sendHtmlWithPdf(
                email, subject, "email/register-statement",
                Map.of(
                        "assetName", asset.getName(),
                        "isin", asset.getIsin() != null ? asset.getIsin() : "—",
                        "trigger", statement.getTrigger().name(),
                        "registryName", registryName),
                pdf, "Registerauszug.pdf");

        if (sent) {
            statement.setDeliveryStatus(DeliveryStatus.DELIVERED);
            statement.setDeliveryChannel("EMAIL");
            statement.setDeliveredAt(Instant.now());
        } else {
            statement.setDeliveryStatus(DeliveryStatus.FAILED);
            statement.setDeliveryChannel("EMAIL");
            statement.setDeliveryError("SMTP delivery failed");
        }
        statementRepository.save(statement);
    }

    /** Re-attempts delivery of statements that previously failed. */
    @Transactional
    public void retryFailedDeliveries() {
        List<RegisterStatement> failed = statementRepository.findByDeliveryStatus(DeliveryStatus.FAILED);
        for (RegisterStatement statement : failed) {
            if (NO_LAWFUL_CHANNEL.equals(statement.getDeliveryErrorCode())) {
                continue; // erasure - retrying cannot succeed and must not reach a tombstoned address
            }
            Asset asset = assetRepository.findById(statement.getAssetId()).orElse(null);
            LegalEntity investor = entityRepository.findById(statement.getInvestorId()).orElse(null);
            AssetHolder holder = holderRepository.findById(statement.getHolderId()).orElse(null);
            if (asset == null || investor == null || holder == null) {
                continue;
            }
            LocalDate issuedDate = statement.getIssuedAt().atZone(ZoneOffset.UTC).toLocalDate();
            DocumentContext ctx = buildContext(asset, holder);
            // Verify the register content is unchanged since issuance before re-delivering.
            // If the data changed, a new CHANGE statement must be issued instead.
            String currentKey = contentKey(asset, holder, investor, statement.getTrigger(), registryName, issuedDate, ctx);
            if (!sha256Hex(currentKey).equals(statement.getContentHash())) {
                log.info("Statement {} content changed since issuance; skipping stale re-delivery",
                        statement.getId());
                continue;
            }
            byte[] pdf = renderStatement(asset, holder, investor, statement.getTrigger(), issuedDate, ctx);
            deliver(statement, asset, investor, pdf);
        }
    }

    /** Only enabled users with a real address: erased ({@code @erased.invalid}) and disabled users never receive mail. */
    static String resolveEmail(List<AppUser> users) {
        return users.stream()
                .filter(AppUser::isEnabled)
                .map(AppUser::getEmail)
                .filter(e -> e != null && !e.isBlank() && !isErasedEmail(e))
                .findFirst()
                .orElse(null);
    }

    private static boolean isErasedEmail(String email) {
        return email != null && email.toLowerCase(java.util.Locale.ROOT).endsWith("@erased.invalid");
    }

    /**
     * Canonical key over the register fields disclosed in this statement.
     * Hashing this — rather than the rendered PDF bytes — produces a stable,
     * independently verifiable digest: PDFBox embeds a random file ID on every
     * save(), so PDF bytes are non-deterministic even with identical content.
     */
    private static String contentKey(Asset asset, AssetHolder holder, LegalEntity investor,
                                     StatementTrigger trigger, String registryName, LocalDate issuedDate,
                                     DocumentContext ctx) {
        StringBuilder key = new StringBuilder(String.join("|",
                issuedDate.toString(),
                trigger.name(),
                nvl(registryName),
                nvl(ctx.profile().docType()),
                asset.getJurisdiction() != null ? asset.getJurisdiction().name() : "",
                nvl(asset.getName()),
                nvl(asset.getIsin()),
                // Holder-level entry type, not the asset's — see the renderer's own note:
                // an asset in Mischbestand can have both individual and collective holders.
                holder.getEntryType() != null ? holder.getEntryType().name() : "",
                nvl(investor.getEntityNumber()),
                ctx.issuer() != null ? nvl(ctx.issuer().getEntityNumber()) : "",
                nvl(holder.getHolderReference()),
                holder.getNominalAmount() != null ? holder.getNominalAmount().toPlainString() : "0",
                nvl(holder.getWalletAddress()),
                nvl(holder.getThirdPartyRights()),
                nvl(holder.getDisposalRestrictions()),
                nvl(holder.getLegalCapacityNote()),
                ctx.bondTerms() != null && ctx.bondTerms().getFaceValue() != null
                        ? ctx.bondTerms().getFaceValue().toPlainString() + nvl(ctx.bondTerms().getCurrencyIso()) : "",
                ctx.termSheet() != null ? nvl(ctx.termSheet().getContentHash()) : ""));
        for (HolderBlock b : ctx.blocks()) {
            key.append("|BLOCK:").append(b.getBlockType()).append(':')
                    .append(nvl(b.getLegalBasis())).append(':').append(nvl(b.getCourtRef()));
        }
        return key.toString();
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return "0x" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
