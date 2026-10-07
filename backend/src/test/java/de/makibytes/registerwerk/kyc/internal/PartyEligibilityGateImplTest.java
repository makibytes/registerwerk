package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PartyEligibilityGateImpl (5C-03)")
class PartyEligibilityGateImplTest {

    private static final String WALLET = "0x" + "ab".repeat(20);

    private final UUID entityId = UUID.randomUUID();
    private LegalEntityRepository entities;
    private ScreeningGate screening;
    private HolderBlockGate blocks;
    private PartyEligibilityGateImpl gate;
    private LegalEntity entity;

    @BeforeEach
    void setUp() {
        entities = mock(LegalEntityRepository.class);
        screening = mock(ScreeningGate.class);
        blocks = mock(HolderBlockGate.class);
        gate = new PartyEligibilityGateImpl(entities, screening, blocks);
        entity = new LegalEntity();
        ReflectionTestUtils.setField(entity, "id", entityId);
        entity.setStatus(EntityStatus.ACTIVE);
        entity.setKycStatus(KycStatus.APPROVED);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
    }

    @Test
    @DisplayName("an ACTIVE, KYC-approved, unblocked, unscreened-hit-free party is eligible")
    void eligible() {
        assertThat(gate.check(entityId, WALLET)).isEmpty();
        assertThatCode(() -> gate.require(entityId, WALLET, "trade")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a SUSPENDED entity is refused (the old trading gate never looked at entity status)")
    void suspendedRefused() {
        entity.setStatus(EntityStatus.SUSPENDED);
        assertThatThrownBy(() -> gate.require(entityId, WALLET, "trade"))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("T6-12: a PENDING_REACTIVATION entity is refused like any non-ACTIVE entity")
    void pendingReactivationRefused() {
        entity.setStatus(EntityStatus.PENDING_REACTIVATION);
        assertThatThrownBy(() -> gate.require(entityId, WALLET, "trade"))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("PENDING_REACTIVATION");
    }

    @Test
    @DisplayName("an unresolved beneficial-owner hit is refused although the entity's own KYC status is still APPROVED")
    void uboHitRefused() {
        when(screening.hasUnresolvedBeneficialOwnerHit(entityId)).thenReturn(true);
        assertThatThrownBy(() -> gate.require(entityId, WALLET, "trade"))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("screening");
    }

    @Test
    @DisplayName("an APPROVED entity whose KYC expiry date has passed is refused")
    void expiredKycRefused() {
        entity.setKycExpiryDate(LocalDate.now().minusDays(1));
        assertThat(gate.check(entityId, WALLET)).anyMatch(r -> r.contains("expired KYC"));
        entity.setKycExpiryDate(LocalDate.now());
        assertThat(gate.check(entityId, WALLET)).isEmpty();
    }

    @Test
    @DisplayName("a not-approved KYC and an active Sperrvermerk are both reported (check() lists every reason)")
    void reportsAllReasons() {
        entity.setKycStatus(KycStatus.IN_PROGRESS);
        when(blocks.isBlocked(any(), any())).thenReturn(true);
        assertThat(gate.check(entityId, WALLET)).hasSize(2);
    }

    @Test
    @DisplayName("an unknown entity is not eligible")
    void unknownEntity() {
        assertThat(gate.check(UUID.randomUUID(), WALLET)).containsExactly("is unknown");
    }

    @Test
    @DisplayName("6-25: entity-only callers (repo desk) see a wallet-only Sperrvermerk on the entity's holder wallets")
    void entityOnlyCheckSeesWalletOnlyBlock() {
        when(blocks.isEntityBlocked(entityId)).thenReturn(true);
        assertThat(gate.check(entityId, null)).hasSize(1);
        assertThatThrownBy(() -> gate.require(entityId, null, "repo desk")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("hardStopsAreASubsetOfReasons: status and sanctions are hard; KYC expiry and Sperrvermerk are not (9A-08)")
    void hardStopsAreASubsetOfReasons() {
        // KYC expired + a legal block: soft only -> no hard stop although the party is not eligible
        entity.setKycExpiryDate(LocalDate.now().minusDays(1));
        when(blocks.isBlocked(entityId, null)).thenReturn(true);
        assertThat(gate.check(entityId, null)).hasSize(2);
        assertThat(gate.hardStops(entityId)).isEmpty();

        // not-approved KYC is soft too
        entity.setKycStatus(KycStatus.IN_PROGRESS);
        assertThat(gate.hardStops(entityId)).isEmpty();

        // entity status and an unresolved sanctions-screening result are hard
        entity.setStatus(EntityStatus.SUSPENDED);
        when(screening.hasUnresolvedHit(entityId)).thenReturn(true);
        assertThat(gate.hardStops(entityId)).hasSize(2);
        assertThat(gate.check(entityId, null)).containsAll(gate.hardStops(entityId));

        // a beneficial-owner hit alone is hard
        entity.setStatus(EntityStatus.ACTIVE);
        when(screening.hasUnresolvedHit(entityId)).thenReturn(false);
        when(screening.hasUnresolvedBeneficialOwnerHit(entityId)).thenReturn(true);
        assertThat(gate.hardStops(entityId)).containsExactly("has an unresolved sanctions-screening result");
    }

    @Test
    @DisplayName("an unknown entity is a hard stop (fail closed)")
    void unknownEntityIsAHardStop() {
        assertThat(gate.hardStops(UUID.randomUUID())).containsExactly("is unknown");
        assertThat(gate.hardStops(null)).containsExactly("is unknown");
    }
}
