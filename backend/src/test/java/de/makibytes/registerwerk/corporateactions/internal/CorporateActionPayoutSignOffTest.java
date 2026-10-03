package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionIssuerAttestationOverriddenEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionIssuerAttestedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionOperatorConfirmedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSignOffVoidedEvent;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 0b C6: the issuer attestation and the operator confirmation of a payout cover the COMPUTED amounts - they are
 * only possible once the action is COMPUTED, are bound to a digest of (entries, total, rounding residual), are voided
 * when the amounts are re-snapshotted or no longer match, and the payout job only starts after both valid signatures.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Corporate-action payout sign-off bound to the computed amounts (C6)")
class CorporateActionPayoutSignOffTest {

    @Mock private CorporateActionRepository repository;
    @Mock private CorporateActionEntryRepository entryRepository;
    @Mock private RecordDatePositionResolver positionResolver;
    @Mock private de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository bondTermsRepository;
    @Mock private CorporateActionSettlementWriter settlementWriter;
    @Mock private AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private CorporateActionProposalValidator proposalValidator;
    @Mock private ApplicationEventPublisher events;
    @Mock private PartyEligibilityGate partyGate;
    @Mock private EntityTaskPort entityTasks;
    @Mock private FinalityGate finalityGate;
    @Mock private RegisterFreshnessGate freshnessGate;

    private CorporateActionService service;
    private final UUID assetId = UUID.randomUUID();
    private final UUID issuer = UUID.randomUUID();
    private final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CorporateActionService(repository, entryRepository, positionResolver, settlementWriter,
                couponPaymentRepository, proposalValidator, events, partyGate, entityTasks, finalityGate, freshnessGate,
                bondTermsRepository, CorporateActionTestSupport.systemRegisterClock(),
                CorporateActionTestSupport.directTransactions());
        lenient().when(repository.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(freshnessGate.blockedReason(any(), any())).thenReturn(Optional.empty());
    }

    private CorporateAction action(CorporateAction.Status status) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", UUID.randomUUID());
        ca.setAssetId(assetId);
        ca.setActionType(CorporateAction.ActionType.COUPON);
        ca.setCurrency("EUR");
        ca.setAmountPerUnit(new BigDecimal("0.05"));
        ca.setStatus(status);
        ca.setRecordDate(LocalDate.now().minusDays(1));
        ca.setPaymentDate(LocalDate.now());
        lenient().when(repository.findById(ca.getId())).thenReturn(Optional.of(ca));
        return ca;
    }

    private List<CorporateActionEntry> entries(CorporateAction ca, String nominalA, String entitlementA) {
        List<CorporateActionEntry> entries = List.of(
                CorporateActionTestSupport.entry(ca.getId(), "0xaaa", nominalA, entitlementA),
                CorporateActionTestSupport.entry(ca.getId(), "0xbbb", "2000", "100.00"));
        lenient().when(entryRepository.findByCorporateActionId(ca.getId())).thenReturn(entries);
        return entries;
    }

    // ── only a COMPUTED action can be signed off ───────────────────────────────

    @Test
    @DisplayName("issuer attestation on an ANNOUNCED action is refused - there are no computed amounts to attest")
    void attestOnAnnouncedIsRefused() {
        CorporateAction ca = action(CorporateAction.Status.ANNOUNCED);

        assertThatThrownBy(() -> service.attestSettlementAsIssuer(assetId, ca.getId(), "ref", issuer, "ISSUER", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPUTED");
        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any(CorporateActionIssuerAttestedEvent.class));
        assertThat(ca.getIssuerAttestedAt()).isNull();
    }

    @Test
    @DisplayName("issuer attestation on a SNAPSHOT_BLOCKED action is refused")
    void attestOnSnapshotBlockedIsRefused() {
        CorporateAction ca = action(CorporateAction.Status.SNAPSHOT_BLOCKED);

        assertThatThrownBy(() -> service.attestSettlementAsIssuer(assetId, ca.getId(), "ref", issuer, "ISSUER", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPUTED");
        assertThat(ca.getIssuerAttestedAt()).isNull();
    }

    @Test
    @DisplayName("the operator's attestation override is refused on a not-yet-computed action too")
    void overrideOnAnnouncedIsRefused() {
        CorporateAction ca = action(CorporateAction.Status.ANNOUNCED);

        assertThatThrownBy(() -> service.overrideIssuerAttestation(ca.getId(), "issuer unreachable", operator, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPUTED");
        verify(events, never()).publishEvent(any(CorporateActionIssuerAttestationOverriddenEvent.class));
    }

    @Test
    @DisplayName("operator confirmation is refused unless the action is COMPUTED, even when an (old) issuer attestation exists")
    void confirmOnAnnouncedIsRefused() {
        CorporateAction ca = action(CorporateAction.Status.ANNOUNCED);
        ca.setIssuerAttestedBy(issuer);
        ca.setIssuerAttestedAt(java.time.Instant.now());

        assertThatThrownBy(() -> service.confirmSettlementAsOperator(ca.getId(), operator, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPUTED");
        assertThat(ca.getDualControlApproverId()).isNull();
        verify(events, never()).publishEvent(any(CorporateActionOperatorConfirmedEvent.class));
    }

    // ── the sign-off is bound to the computed amounts ──────────────────────────

    @Test
    @DisplayName("the attestation stores the digest of the computed entries, total and rounding residual")
    void attestationIsBoundToTheDigest() {
        CorporateAction ca = action(CorporateAction.Status.COMPUTED);
        ca.setTotalAmount(new BigDecimal("150.00"));
        ca.setRoundingResidual(BigDecimal.ZERO);
        List<CorporateActionEntry> entries = entries(ca, "1000", "50.00");
        CorporateActionTestSupport.computed(ca, entries);

        CorporateAction result = service.attestSettlementAsIssuer(assetId, ca.getId(), "ref", issuer, "ISSUER", false);

        assertThat(result.getIssuerAttestedDigest()).isEqualTo(CorporateActionTestSupport.digest(ca, entries));
        assertThat(result.getIssuerAttestedAt()).isNotNull();
    }

    @Test
    @DisplayName("an attestation that names a different digest than the amounts now stand at is refused")
    void attestationWithStaleExpectedDigestIsRefused() {
        CorporateAction ca = action(CorporateAction.Status.COMPUTED);
        ca.setTotalAmount(new BigDecimal("150.00"));
        List<CorporateActionEntry> entries = entries(ca, "1000", "50.00");
        CorporateActionTestSupport.computed(ca, entries);

        assertThatThrownBy(() -> service.attestSettlementAsIssuer(assetId, ca.getId(), "ref", issuer, "ISSUER", false,
                "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
        assertThat(ca.getIssuerAttestedAt()).isNull();
    }

    @Test
    @DisplayName("an attestation is refused when the entries no longer match the digest stored at compute time")
    void attestationWhenEntriesDriftedFromTheComputedDigestIsRefused() {
        CorporateAction ca = action(CorporateAction.Status.COMPUTED);
        ca.setTotalAmount(new BigDecimal("150.00"));
        List<CorporateActionEntry> computedEntries = List.of(
                CorporateActionTestSupport.entry(ca.getId(), "0xaaa", "1000", "50.00"));
        CorporateActionTestSupport.computed(ca, computedEntries);
        // somebody changed an entitlement after the snapshot
        when(entryRepository.findByCorporateActionId(ca.getId())).thenReturn(List.of(
                CorporateActionTestSupport.entry(ca.getId(), "0xaaa", "1000", "5000.00")));

        assertThatThrownBy(() -> service.attestSettlementAsIssuer(assetId, ca.getId(), "ref", issuer, "ISSUER", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed");
        assertThat(ca.getIssuerAttestedAt()).isNull();
    }

    @Test
    @DisplayName("operator confirmation binds the same digest, and is refused when the issuer attested different amounts")
    void confirmationIsBoundAndMustMatchTheIssuersDigest() {
        CorporateAction ca = action(CorporateAction.Status.COMPUTED);
        ca.setTotalAmount(new BigDecimal("150.00"));
        List<CorporateActionEntry> entries = entries(ca, "1000", "50.00");
        CorporateActionTestSupport.computed(ca, entries);
        ca.setIssuerAttestedBy(issuer);
        ca.setIssuerAttestedAt(java.time.Instant.now());
        ca.setIssuerAttestedDigest("f".repeat(64)); // attested something else

        assertThatThrownBy(() -> service.confirmSettlementAsOperator(ca.getId(), operator, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attest");
        assertThat(ca.getDualControlApproverId()).isNull();

        ca.setIssuerAttestedDigest(ca.getPayoutDigest());
        CorporateAction confirmed = service.confirmSettlementAsOperator(ca.getId(), operator, "REGISTRY_ADMIN");
        assertThat(confirmed.getOperatorConfirmedDigest()).isEqualTo(ca.getPayoutDigest());
    }

    // ── re-snapshot / recompute voids the sign-offs ────────────────────────────

    @Test
    @DisplayName("a (re)snapshot voids an earlier attestation and confirmation, audited")
    void reSnapshotVoidsSignOffs() {
        CorporateAction ca = action(CorporateAction.Status.SNAPSHOT_BLOCKED);
        // sign-offs given on the old amounts (e.g. issued while ANNOUNCED before this fix)
        ca.setIssuerAttestedBy(issuer);
        ca.setIssuerAttestedAt(java.time.Instant.now());
        ca.setIssuerAttestedDigest("a".repeat(64));
        ca.setDualControlApproverId(operator);
        ca.setDualControlApprovedAt(java.time.Instant.now());
        ca.setOperatorConfirmedDigest("a".repeat(64));
        AssetHolder holder = new AssetHolder();
        ReflectionTestUtils.setField(holder, "id", UUID.randomUUID());
        holder.setInvestorId(UUID.randomUUID());
        holder.setWalletAddress("0xaaa");
        holder.setNominalAmount(new BigDecimal("1000"));
        when(repository.findReadyToCompute(any())).thenReturn(List.of(ca));
        when(repository.findDueForSettlement(any())).thenReturn(List.of());
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        when(entryRepository.existsByCorporateActionId(ca.getId())).thenReturn(false);
        when(positionResolver.resolve(any(), any())).thenReturn(CorporateActionTestSupport.positionsOf(List.of(holder)));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        assertThat(ca.getPayoutDigest()).isNotBlank();
        assertThat(ca.getIssuerAttestedAt()).isNull();
        assertThat(ca.getIssuerAttestedBy()).isNull();
        assertThat(ca.getIssuerAttestedDigest()).isNull();
        assertThat(ca.getDualControlApproverId()).isNull();
        assertThat(ca.getDualControlApprovedAt()).isNull();
        assertThat(ca.getOperatorConfirmedDigest()).isNull();
        ArgumentCaptor<CorporateActionSignOffVoidedEvent> voided = ArgumentCaptor.forClass(CorporateActionSignOffVoidedEvent.class);
        verify(events).publishEvent(voided.capture());
        assertThat(voided.getValue().issuerHadAttested()).isTrue();
        assertThat(voided.getValue().operatorHadConfirmed()).isTrue();
    }

    // ── the payout job only starts after both valid signatures ─────────────────

    private void givenDue(CorporateAction ca) {
        when(repository.findReadyToCompute(any())).thenReturn(List.of());
        when(repository.findDueForSettlement(any())).thenReturn(List.of(ca));
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        lenient().when(repository.findTokenStandardByCorpAction(ca.getId())).thenReturn(null);
        lenient().when(finalityGate.check(any(), any(), any(), any())).thenReturn(new FinalityDecision.Allowed(FinalityLevel.FINALIZED));
    }

    @Test
    @DisplayName("both signatures over the current digest: the payout job starts the settlement")
    void settlementStartsWithBothValidSignatures() {
        CorporateAction ca = action(CorporateAction.Status.COMPUTED);
        ca.setTotalAmount(new BigDecimal("150.00"));
        List<CorporateActionEntry> entries = entries(ca, "1000", "50.00");
        CorporateActionTestSupport.signedOff(ca, entries, issuer, operator);
        givenDue(ca);

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.AWAITING_SETTLEMENT);
        verify(events).publishEvent(any(CorporateActionSettlementRequestedEvent.class));
    }

    @Test
    @DisplayName("a hash mismatch after the amounts changed: nothing is paid, both sign-offs are voided and audited")
    void settlementRefusedOnDigestMismatchVoidsTheSignOffs() {
        CorporateAction ca = action(CorporateAction.Status.COMPUTED);
        ca.setTotalAmount(new BigDecimal("150.00"));
        List<CorporateActionEntry> signedEntries = entries(ca, "1000", "50.00");
        CorporateActionTestSupport.signedOff(ca, signedEntries, issuer, operator);
        // the entries are re-snapshotted after the sign-off
        when(entryRepository.findByCorporateActionId(ca.getId())).thenReturn(List.of(
                CorporateActionTestSupport.entry(ca.getId(), "0xaaa", "9000", "450.00")));
        givenDue(ca);

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        assertThat(ca.getIssuerAttestedAt()).isNull();
        assertThat(ca.getDualControlApproverId()).isNull();
        verify(events, never()).publishEvent(any(CorporateActionSettlementRequestedEvent.class));
        verify(events).publishEvent(any(CorporateActionSignOffVoidedEvent.class));
    }

    @Test
    @DisplayName("signatures that carry no digest (given before the amounts were bound) do not start a payout")
    void settlementRefusedWhenSignaturesCarryNoDigest() {
        CorporateAction ca = action(CorporateAction.Status.COMPUTED);
        List<CorporateActionEntry> entries = entries(ca, "1000", "50.00");
        CorporateActionTestSupport.computed(ca, entries);
        ca.setIssuerAttestedBy(issuer);
        ca.setIssuerAttestedAt(java.time.Instant.now());
        ca.setDualControlApproverId(operator);
        ca.setDualControlApprovedAt(java.time.Instant.now());
        givenDue(ca);

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        verify(events, never()).publishEvent(any(CorporateActionSettlementRequestedEvent.class));
    }
}
