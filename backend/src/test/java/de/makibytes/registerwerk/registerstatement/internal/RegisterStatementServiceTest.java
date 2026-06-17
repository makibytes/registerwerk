package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.EntryType;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegisterStatementService §19 eligibility and delivery")
class RegisterStatementServiceTest {

    private final RegisterStatementRepository statementRepository = mock(RegisterStatementRepository.class);
    private final AssetHolderRepository holderRepository = mock(AssetHolderRepository.class);
    private final AssetRepository assetRepository = mock(AssetRepository.class);
    private final LegalEntityRepository entityRepository = mock(LegalEntityRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final EmailPort emailPort = mock(EmailPort.class);

    private RegisterStatementService newService() {
        return new RegisterStatementService(statementRepository, holderRepository, assetRepository,
                entityRepository, userRepository, emailPort, "Test Registry", "DE");
    }

    private AssetHolder holder(EntryType entryType, boolean consumer) {
        AssetHolder h = new AssetHolder();
        h.setId(UUID.randomUUID());
        h.setAssetId(UUID.randomUUID());
        h.setInvestorId(UUID.randomUUID());
        h.setWalletAddress("0x1234567890abcdef");
        h.setNominalAmount(new BigDecimal("100"));
        h.setEntryType(entryType);
        h.setIsConsumer(consumer);
        h.setHolderReference("RW-REF-0001");
        return h;
    }

    private void wireSupportingData(AssetHolder h) {
        Asset asset = new Asset();
        asset.setEntryType(h.getEntryType());
        // name/isin via reflection-free setters
        asset.setName("Test Bond 2026");
        LegalEntity investor = new LegalEntity();
        investor.setId(h.getInvestorId());
        when(assetRepository.findById(h.getAssetId())).thenReturn(Optional.of(asset));
        when(entityRepository.findById(h.getInvestorId())).thenReturn(Optional.of(investor));
        AppUser user = new AppUser();
        user.setEmail("investor@example.com");
        lenient().when(userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(h.getInvestorId()))
                .thenReturn(List.of(user));
        when(statementRepository.save(any(RegisterStatement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("collective-entry holder is never eligible for §19(2) statements")
    void collectiveEntryNotEligible() {
        AssetHolder h = holder(EntryType.COLLECTIVE, true);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));

        Optional<RegisterStatement> result =
                newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        assertThat(result).isEmpty();
        verify(statementRepository, never()).save(any());
    }

    @Test
    @DisplayName("single-entry non-consumer is not owed periodic statements")
    void singleEntryNonConsumerNotEligibleForPeriodic() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, false);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));

        Optional<RegisterStatement> result =
                newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        assertThat(result).isEmpty();
        verify(statementRepository, never()).save(any());
    }

    @Test
    @DisplayName("single-entry consumer receives a delivered annual statement")
    void singleEntryConsumerEligible() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(emailPort.sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(true);

        Optional<RegisterStatement> result =
                newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        assertThat(result).isPresent();
        assertThat(result.get().getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(result.get().getContentHash()).startsWith("0x");
    }

    @Test
    @DisplayName("on-demand statement is allowed for a single-entry non-consumer (§19(1))")
    void onDemandAllowedForNonConsumer() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, false);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(emailPort.sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(true);

        Optional<RegisterStatement> result = newService().issueOnDemand(h.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getTrigger()).isEqualTo(StatementTrigger.ON_DEMAND);
    }

    @Test
    @DisplayName("failed e-mail delivery is recorded as FAILED, not thrown")
    void deliveryFailureRecorded() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(emailPort.sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(false);

        Optional<RegisterStatement> result =
                newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        assertThat(result).isPresent();
        assertThat(result.get().getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("retry re-renders from statement.issuedAt, not current time — same hash required for re-delivery")
    void retryUsesOriginalIssuedDate() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true);

        // Use the same objects for both the pre-computed hash and the mocked repositories,
        // so the render() calls inside retryFailedDeliveries() produce identical bytes.
        Asset asset = new Asset();
        asset.setName("Test Bond 2026");
        asset.setEntryType(EntryType.INDIVIDUAL);
        LegalEntity investor = new LegalEntity();
        investor.setId(h.getInvestorId());
        AppUser user = new AppUser();
        user.setEmail("investor@example.com");

        Instant issuedAt = Instant.parse("2026-01-10T09:00:00Z");
        java.time.LocalDate issuedDate = issuedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();

        // Compute the content hash exactly as RegisterStatementService does.
        String key = String.join("|",
                issuedDate.toString(), StatementTrigger.ANNUAL.name(), "Test Registry",
                "Test Bond 2026", "", EntryType.INDIVIDUAL.name(),
                "", "RW-REF-0001", "100", "0x1234567890abcdef", "", "", "");
        String contentHash = sha256Hex(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        RegisterStatement failed = new RegisterStatement();
        failed.setIssuedAt(issuedAt);
        failed.setHolderId(h.getId());
        failed.setAssetId(h.getAssetId());
        failed.setInvestorId(h.getInvestorId());
        failed.setTrigger(StatementTrigger.ANNUAL);
        failed.setNominalAmount(h.getNominalAmount());
        failed.setWalletAddress(h.getWalletAddress());
        failed.setHolderReference(h.getHolderReference());
        failed.setDeliveryStatus(DeliveryStatus.FAILED);
        failed.setContentHash(contentHash);

        when(statementRepository.findByDeliveryStatus(DeliveryStatus.FAILED)).thenReturn(List.of(failed));
        when(assetRepository.findById(h.getAssetId())).thenReturn(Optional.of(asset));
        when(entityRepository.findById(h.getInvestorId())).thenReturn(Optional.of(investor));
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        when(userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(h.getInvestorId()))
                .thenReturn(List.of(user));
        when(statementRepository.save(any(RegisterStatement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(emailPort.sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(true);

        newService().retryFailedDeliveries();

        verify(emailPort).sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            return "0x" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("no deliverable e-mail yields FAILED with a clear error")
    void noEmailYieldsFailed() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        Asset asset = new Asset();
        asset.setName("Test Bond");
        LegalEntity investor = new LegalEntity();
        when(assetRepository.findById(h.getAssetId())).thenReturn(Optional.of(asset));
        when(entityRepository.findById(h.getInvestorId())).thenReturn(Optional.of(investor));
        when(userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(h.getInvestorId()))
                .thenReturn(List.of());
        when(statementRepository.save(any(RegisterStatement.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<RegisterStatement> result =
                newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        assertThat(result).isPresent();
        assertThat(result.get().getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(result.get().getDeliveryError()).contains("No deliverable e-mail");
        verify(emailPort, never()).sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString());
    }
}
