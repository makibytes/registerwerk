package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.notification.api.EmailPort;
import de.makibytes.registerwerk.registerstatement.api.DeliveryStatus;
import de.makibytes.registerwerk.registerstatement.api.RegisterStatement;
import de.makibytes.registerwerk.registerstatement.api.RegisterStatementRepository;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
    private final EmailPort emailService;
    private final String registryName;
    private final String registryCountry;

    public RegisterStatementService(
            RegisterStatementRepository statementRepository,
            AssetHolderRepository holderRepository,
            AssetRepository assetRepository,
            LegalEntityRepository entityRepository,
            AppUserRepository userRepository,
            EmailPort emailService,
            @Value("${registerwerk.registry.name:Registerwerk eWpG-Registry}") String registryName,
            @Value("${registerwerk.registry.country:DE}") String registryCountry) {
        this.statementRepository = statementRepository;
        this.holderRepository = holderRepository;
        this.assetRepository = assetRepository;
        this.entityRepository = entityRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.registryName = registryName;
        this.registryCountry = registryCountry;
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

        byte[] pdf = RegisterStatementPdfRenderer.render(
                asset, holder, investor, trigger, registryName, registryCountry);

        RegisterStatement statement = new RegisterStatement();
        statement.setHolderId(holder.getId());
        statement.setAssetId(holder.getAssetId());
        statement.setInvestorId(holder.getInvestorId());
        statement.setTrigger(trigger);
        statement.setNominalAmount(holder.getNominalAmount());
        statement.setWalletAddress(holder.getWalletAddress());
        statement.setHolderReference(holder.getHolderReference());
        statement.setContentHash(sha256Hex(pdf));
        statement.setDeliveryStatus(DeliveryStatus.PENDING);
        statement = statementRepository.save(statement);

        // Update the holder's annual-statement clock regardless of delivery outcome:
        // the obligation is to issue, and the record now exists.
        holder.setLastStatementAt(Instant.now());
        holderRepository.save(holder);

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
        boolean singleEntry = holder.getEntryType()
                == de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL;
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

    private void deliver(RegisterStatement statement, Asset asset, LegalEntity investor, byte[] pdf) {
        String email = resolveEmail(investor.getId());
        if (email == null) {
            statement.setDeliveryStatus(DeliveryStatus.FAILED);
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
            Asset asset = assetRepository.findById(statement.getAssetId()).orElse(null);
            LegalEntity investor = entityRepository.findById(statement.getInvestorId()).orElse(null);
            AssetHolder holder = holderRepository.findById(statement.getHolderId()).orElse(null);
            if (asset == null || investor == null || holder == null) {
                continue;
            }
            byte[] pdf = RegisterStatementPdfRenderer.render(
                    asset, holder, investor, statement.getTrigger(), registryName, registryCountry);
            // Re-render must reproduce the same content hash; if the register changed
            // since, that is a new CHANGE statement, not a re-delivery of this one.
            if (!sha256Hex(pdf).equals(statement.getContentHash())) {
                log.info("Statement {} content changed since issuance; skipping stale re-delivery",
                        statement.getId());
                continue;
            }
            deliver(statement, asset, investor, pdf);
        }
    }

    private String resolveEmail(UUID investorId) {
        List<AppUser> users = userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(investorId);
        return users.stream()
                .map(AppUser::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
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
