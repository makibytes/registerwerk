package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.InvestorLimit;
import de.makibytes.registerwerk.asset.api.InvestorLimitRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestorLimitService unit tests (Track 5-2)")
class InvestorLimitServiceTest {

    @Mock private InvestorLimitRepository repository;
    @Mock private ApplicationEventPublisher events;

    private InvestorLimitService service;

    private final UUID assetId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InvestorLimitService(repository, events);
        lenient().when(repository.save(any(InvestorLimit.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Asset asset(BigDecimal minDefault, BigDecimal maxDefault) {
        Asset a = new Asset();
        a.setId(assetId);
        a.setMinInvestmentAmount(minDefault);
        a.setMaxHoldingAmount(maxDefault);
        return a;
    }

    @Test
    @DisplayName("effectiveMinInvestment falls back to the asset default when no override exists")
    void effectiveMinInvestment_fallsBackToAssetDefault() {
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.empty());

        BigDecimal result = service.effectiveMinInvestment(asset(new BigDecimal("1000"), null), investorId);

        assertThat(result).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("effectiveMinInvestment uses the override when one exists, even if it's lower than the default")
    void effectiveMinInvestment_usesOverride() {
        InvestorLimit override = new InvestorLimit();
        override.setMinInvestmentOverride(new BigDecimal("100"));
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.of(override));

        BigDecimal result = service.effectiveMinInvestment(asset(new BigDecimal("1000"), null), investorId);

        assertThat(result).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("effectiveMaxHolding is null (unrestricted) when neither an override nor an asset default exists")
    void effectiveMaxHolding_nullWhenUnrestricted() {
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.empty());

        assertThat(service.effectiveMaxHolding(asset(null, null), investorId)).isNull();
    }

    @Test
    @DisplayName("isLockedUp is true only while today is before lockupUntil")
    void isLockedUp_trueBeforeLockupDate() {
        InvestorLimit limit = new InvestorLimit();
        limit.setLockupUntil(LocalDate.now().plusDays(30));
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.of(limit));

        assertThat(service.isLockedUp(assetId, investorId)).isTrue();
    }

    @Test
    @DisplayName("isLockedUp is false once the lockup date has passed")
    void isLockedUp_falseAfterLockupDate() {
        InvestorLimit limit = new InvestorLimit();
        limit.setLockupUntil(LocalDate.now().minusDays(1));
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.of(limit));

        assertThat(service.isLockedUp(assetId, investorId)).isFalse();
    }

    @Test
    @DisplayName("isLockedUp is false when no limit row exists at all")
    void isLockedUp_falseWhenNoRow() {
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.empty());

        assertThat(service.isLockedUp(assetId, investorId)).isFalse();
    }

    @Test
    @DisplayName("setLimit creates a new row and publishes an event when none exists yet")
    void setLimit_createsNewRow() {
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.empty());
        UUID actorId = UUID.randomUUID();

        InvestorLimit result = service.setLimit(
                assetId, investorId, new BigDecimal("500"), new BigDecimal("50000"), LocalDate.now().plusYears(1), actorId);

        assertThat(result.getAssetId()).isEqualTo(assetId);
        assertThat(result.getInvestorEntityId()).isEqualTo(investorId);
        assertThat(result.getMinInvestmentOverride()).isEqualByComparingTo("500");
        assertThat(result.getMaxHoldingOverride()).isEqualByComparingTo("50000");
        assertThat(result.getUpdatedBy()).isEqualTo(actorId);
        verify(events).publishEvent(any(de.makibytes.registerwerk.asset.events.InvestorLimitSetEvent.class));
    }

    @Test
    @DisplayName("setLimit replaces an existing row's fields rather than creating a duplicate")
    void setLimit_replacesExistingRow() {
        InvestorLimit existing = new InvestorLimit();
        existing.setAssetId(assetId);
        existing.setInvestorEntityId(investorId);
        existing.setMinInvestmentOverride(new BigDecimal("100"));
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.of(existing));

        InvestorLimit result = service.setLimit(assetId, investorId, new BigDecimal("999"), null, null, UUID.randomUUID());

        assertThat(result).isSameAs(existing);
        assertThat(result.getMinInvestmentOverride()).isEqualByComparingTo("999");
    }

    @Test
    @DisplayName("deleteLimit throws for a non-existent override")
    void deleteLimit_throwsWhenNotFound() {
        when(repository.findByAssetIdAndInvestorEntityId(assetId, investorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteLimit(assetId, investorId))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
