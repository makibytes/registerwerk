package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDocumentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.api.JurisdictionRequirementConfig;
import de.makibytes.registerwerk.kyc.api.RegisterDocumentProfile;
import de.makibytes.registerwerk.notification.api.EmailPort;
import de.makibytes.registerwerk.registerstatement.api.DeliveryStatus;
import de.makibytes.registerwerk.registerstatement.api.RegisterStatement;
import de.makibytes.registerwerk.registerstatement.api.RegisterStatementRepository;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import de.makibytes.registerwerk.registerstatement.events.RegisterStatementIssuedEvent;
import de.makibytes.registerwerk.shared.DocumentSigningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegisterStatementService §19 eligibility and delivery")
class RegisterStatementServiceTest {

    private static final RegisterDocumentProfile TEST_PROFILE = new RegisterDocumentProfile(
            "REGISTERAUSZUG", "Registerauszug",
            "Register statement pursuant to § 19 eWpG (Gesetz über elektronische Wertpapiere)", null, true);

    private final RegisterStatementRepository statementRepository = mock(RegisterStatementRepository.class);
    private final AssetHolderRepository holderRepository = mock(AssetHolderRepository.class);
    private final AssetRepository assetRepository = mock(AssetRepository.class);
    private final LegalEntityRepository entityRepository = mock(LegalEntityRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final HolderBlockRepository blockRepository = mock(HolderBlockRepository.class);
    private final AssetBondTermsRepository bondTermsRepository = mock(AssetBondTermsRepository.class);
    private final AssetDocumentRepository documentRepository = mock(AssetDocumentRepository.class);
    private final JurisdictionRequirementConfig jurisdictionConfig = mock(JurisdictionRequirementConfig.class);
    private final EmailPort emailPort = mock(EmailPort.class);
    private final DocumentSigningService signingService = mock(DocumentSigningService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final de.makibytes.registerwerk.finality.api.FinalityGate finalityGate =
            mock(de.makibytes.registerwerk.finality.api.FinalityGate.class);

    private RegisterStatementService newService() {
        return new RegisterStatementService(statementRepository, holderRepository, assetRepository,
                entityRepository, userRepository, blockRepository, bondTermsRepository, documentRepository,
                jurisdictionConfig, emailPort, signingService, eventPublisher, finalityGate, "Test Registry", "DE");
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
        stubDocumentProfile();
    }

    /**
     * The document-context lookups run unconditionally once a holder passes the
     * eligibility gate — stub them broadly (lenient, since not every test path
     * reaches {@code buildContext}) so tests only need to override what they
     * specifically care about.
     */
    private void stubDocumentProfile() {
        lenient().when(jurisdictionConfig.resolveRegisterDocumentProfile(any(), anyBoolean()))
                .thenReturn(TEST_PROFILE);
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
    @DisplayName("a chain-derived holder's issuance is gated: FinalityGate.require is called with REGISTER_STATEMENT_ISSUE")
    void chainDerivedHolder_callsFinalityGate() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true);
        h.setChainDerived(true);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(emailPort.sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(true);

        newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        verify(finalityGate).require(
                de.makibytes.registerwerk.finality.api.GatedOperation.REGISTER_STATEMENT_ISSUE,
                h.getAssetId(), null, de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED);
    }

    @Test
    @DisplayName("a non-chain-derived (manually maintained) holder's issuance never consults the gate")
    void nonChainDerivedHolder_neverCallsFinalityGate() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true); // chainDerived defaults to false
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(emailPort.sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(true);

        newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        verify(finalityGate, never()).require(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a FinalityGate rejection propagates and the statement is never persisted")
    void financeGateBlocked_propagatesAndSkipsPersistence() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true);
        h.setChainDerived(true);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        org.mockito.Mockito.doThrow(new de.makibytes.registerwerk.finality.api.FinalityNotReachedException(
                        new de.makibytes.registerwerk.finality.api.FinalityDecision.Blocked(
                                de.makibytes.registerwerk.finality.api.GatedOperation.REGISTER_STATEMENT_ISSUE,
                                h.getAssetId(), de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED,
                                de.makibytes.registerwerk.finality.api.FinalityLevel.SAFE,
                                de.makibytes.registerwerk.finality.api.FinalityDecision.Blocked.Reason.BELOW_REQUIRED,
                                "not yet final")))
                .when(finalityGate).require(any(), any(), any(), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL))
                .isInstanceOf(de.makibytes.registerwerk.finality.api.FinalityNotReachedException.class);

        verify(statementRepository, never()).save(any());
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

        // Compute the content hash exactly as RegisterStatementService.contentKey() does:
        // issuedDate | trigger | registryName | docType | jurisdiction | assetName | isin |
        // holderEntryType | investorEntityNumber | issuerEntityNumber | holderReference |
        // nominalAmount | walletAddress | thirdPartyRights | disposalRestrictions |
        // legalCapacityNote | bondTermsFingerprint | termSheetHash (no active Sperrvermerke here).
        String key = String.join("|",
                issuedDate.toString(), StatementTrigger.ANNUAL.name(), "Test Registry",
                TEST_PROFILE.docType(), "", "Test Bond 2026", "", EntryType.INDIVIDUAL.name(),
                "", "", "RW-REF-0001", "100", "0x1234567890abcdef", "", "", "", "", "");
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
        stubDocumentProfile();

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
        stubDocumentProfile();

        Optional<RegisterStatement> result =
                newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        assertThat(result).isPresent();
        assertThat(result.get().getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(result.get().getDeliveryError()).contains("No deliverable e-mail");
        verify(emailPort, never()).sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("renderForDownload logs an ON_DEMAND statement and dedupes same-day repeat downloads")
    void renderForDownloadLogsAndDedupesSameDay() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, false);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(statementRepository.findByHolderIdOrderByIssuedAtDesc(h.getId())).thenReturn(List.of());

        Optional<byte[]> first = newService().renderForDownload(h.getId());
        assertThat(first).isPresent();
        verify(statementRepository, times(1)).save(any(RegisterStatement.class));

        // Simulate the row saved by the first call now existing, issued "today".
        RegisterStatement issuedToday = new RegisterStatement();
        issuedToday.setTrigger(StatementTrigger.ON_DEMAND);
        issuedToday.setIssuedAt(Instant.now());
        when(statementRepository.findByHolderIdOrderByIssuedAtDesc(h.getId())).thenReturn(List.of(issuedToday));

        Optional<byte[]> second = newService().renderForDownload(h.getId());
        assertThat(second).isPresent();
        // Still only ever saved once — the same-day repeat download is deduped, not re-logged.
        verify(statementRepository, times(1)).save(any(RegisterStatement.class));
    }

    @Test
    @DisplayName("renderForDownload for a COLLECTIVE holder yields a non-statutory holding confirmation, still logged")
    void renderForDownloadCollectiveYieldsHoldingConfirmation() {
        AssetHolder h = holder(EntryType.COLLECTIVE, false);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(statementRepository.findByHolderIdOrderByIssuedAtDesc(h.getId())).thenReturn(List.of());
        when(jurisdictionConfig.resolveRegisterDocumentProfile(any(), eq(false)))
                .thenReturn(new RegisterDocumentProfile("BESTANDSBESTAETIGUNG", "Bestandsbestätigung",
                        "Holding confirmation for a collectively held position", "nominee disclaimer", false));

        Optional<byte[]> result = newService().renderForDownload(h.getId());

        assertThat(result).isPresent();
        verify(statementRepository).save(any(RegisterStatement.class));
    }

    @Test
    @DisplayName("renderForDownload returns empty for an unknown holder, without touching the register")
    void renderForDownloadUnknownHolderReturnsEmpty() {
        UUID unknown = UUID.randomUUID();
        when(holderRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(newService().renderForDownload(unknown)).isEmpty();
        verify(statementRepository, never()).save(any());
    }

    @Test
    @DisplayName("issueForHolder publishes RegisterStatementIssuedEvent")
    void issueForHolderPublishesAuditEvent() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(emailPort.sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(true);

        newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        ArgumentCaptor<RegisterStatementIssuedEvent> captor = ArgumentCaptor.forClass(RegisterStatementIssuedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().holderId()).isEqualTo(h.getId());
        assertThat(captor.getValue().assetId()).isEqualTo(h.getAssetId());
        assertThat(captor.getValue().trigger()).isEqualTo(StatementTrigger.ANNUAL);
    }

    @Test
    @DisplayName("renderForDownload publishes RegisterStatementIssuedEvent for a newly logged issuance")
    void renderForDownloadPublishesAuditEvent() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, false);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(statementRepository.findByHolderIdOrderByIssuedAtDesc(h.getId())).thenReturn(List.of());

        newService().renderForDownload(h.getId());

        ArgumentCaptor<RegisterStatementIssuedEvent> captor = ArgumentCaptor.forClass(RegisterStatementIssuedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().trigger()).isEqualTo(StatementTrigger.ON_DEMAND);
    }

    @Test
    @DisplayName("statement PDF is PAdES-signed when a signing keystore is configured")
    void issueForHolderSignsWhenConfigured() {
        AssetHolder h = holder(EntryType.INDIVIDUAL, true);
        when(holderRepository.findById(h.getId())).thenReturn(Optional.of(h));
        wireSupportingData(h);
        when(emailPort.sendHtmlWithPdf(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(true);
        when(signingService.isConfigured()).thenReturn(true);
        when(signingService.signPdf(any(), anyString())).thenReturn("signed-bytes".getBytes());

        newService().issueForHolder(h.getId(), StatementTrigger.ANNUAL);

        verify(signingService).signPdf(any(), anyString());
    }
}
