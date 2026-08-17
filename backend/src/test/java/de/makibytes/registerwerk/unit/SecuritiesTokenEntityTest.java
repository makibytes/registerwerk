package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.deployment.api.AssetSlot;
import de.makibytes.registerwerk.deployment.api.AssetTokenUnit;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.MintControlRule;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity accessor coverage for the domain additions:
 * AssetBondTerms, AssetVaultState, VaultNavStrike, VaultRequest,
 * AssetSlot, AssetTokenUnit, AssetCouponPayment.
 */
@DisplayName("Securities token entity getter/setter coverage")
class SecuritiesTokenEntityTest {

    @Test
    void assetBondTerms_gettersAndSetters() {
        AssetBondTerms t = new AssetBondTerms();
        UUID id = UUID.randomUUID();
        t.setAssetId(id);
        t.setFaceValue(BigDecimal.valueOf(1000));
        t.setCurrencyIso("EUR");
        t.setIssueDate(LocalDate.of(2025, 1, 1));
        t.setMaturityDate(LocalDate.of(2030, 12, 31));
        t.setCouponRate(BigDecimal.valueOf(0.05));
        t.setReferenceRate("EURIBOR_3M");
        t.setSpread(BigDecimal.valueOf(0.01));
        t.setDayCount(DayCountConvention.ACT_360);
        t.setPaymentFrequency(PaymentFrequency.SEMI_ANNUAL);
        t.setCallable(true);
        t.setCallSchedule(List.of(Map.of("callDate", "2028-06-01", "callPrice", "101")));
        t.setBondStatus(BondStatus.ACTIVE);

        assertThat(t.getAssetId()).isEqualTo(id);
        assertThat(t.getFaceValue()).isEqualByComparingTo("1000");
        assertThat(t.getCurrencyIso()).isEqualTo("EUR");
        assertThat(t.getIssueDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(t.getMaturityDate()).isEqualTo(LocalDate.of(2030, 12, 31));
        assertThat(t.getCouponRate()).isEqualByComparingTo("0.05");
        assertThat(t.getReferenceRate()).isEqualTo("EURIBOR_3M");
        assertThat(t.getSpread()).isEqualByComparingTo("0.01");
        assertThat(t.getDayCount()).isEqualTo(DayCountConvention.ACT_360);
        assertThat(t.getPaymentFrequency()).isEqualTo(PaymentFrequency.SEMI_ANNUAL);
        assertThat(t.isCallable()).isTrue();
        assertThat(t.getCallSchedule()).hasSize(1);
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.ACTIVE);
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getUpdatedAt()).isNotNull();
    }

    @Test
    void allDayCountConventions_accessible() {
        assertThat(DayCountConvention.values()).hasSize(5);
        assertThat(DayCountConvention.valueOf("ACT_365")).isEqualTo(DayCountConvention.ACT_365);
    }

    @Test
    void allPaymentFrequencies_accessible() {
        assertThat(PaymentFrequency.values()).hasSize(5);
        assertThat(PaymentFrequency.valueOf("ANNUAL")).isEqualTo(PaymentFrequency.ANNUAL);
        assertThat(PaymentFrequency.valueOf("ZERO")).isEqualTo(PaymentFrequency.ZERO);
    }

    @Test
    void allBondStatuses_accessible() {
        assertThat(BondStatus.values()).hasSize(5);
        assertThat(BondStatus.valueOf("CALLED")).isEqualTo(BondStatus.CALLED);
    }

    @Test
    void assetVaultState_gettersAndSetters() {
        AssetVaultState s = new AssetVaultState();
        UUID id = UUID.randomUUID();
        UUID underlyingId = UUID.randomUUID();
        Instant now = Instant.now();
        s.setAssetId(id);
        s.setUnderlyingAssetId(underlyingId);
        s.setDepositCap(BigInteger.valueOf(1_000_000));
        s.setMinSettlementDelay(86400);
        s.setLatestNavPerShare(BigDecimal.valueOf(1.05));
        s.setLatestNavStrikeAt(now);
        s.setLatestNavReportHash(new byte[]{1, 2, 3});

        assertThat(s.getAssetId()).isEqualTo(id);
        assertThat(s.getUnderlyingAssetId()).isEqualTo(underlyingId);
        assertThat(s.getDepositCap()).isEqualTo(BigInteger.valueOf(1_000_000));
        assertThat(s.getMinSettlementDelay()).isEqualTo(86400);
        assertThat(s.getLatestNavPerShare()).isEqualByComparingTo("1.05");
        assertThat(s.getLatestNavStrikeAt()).isEqualTo(now);
        assertThat(s.getLatestNavReportHash()).hasSize(3);
        assertThat(s.getCreatedAt()).isNotNull();
        assertThat(s.getUpdatedAt()).isNotNull();
    }

    @Test
    void vaultNavStrike_gettersAndSetters() {
        VaultNavStrike strike = new VaultNavStrike();
        UUID assetId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        strike.setAssetId(assetId);
        strike.setStrikeId(1L);
        strike.setNavPerShare(BigDecimal.valueOf(1.05));
        strike.setEffectiveAt(now);
        strike.setReportHash(new byte[]{4, 5, 6});
        strike.setReportDocId(docId);
        strike.setStruckBy(actorId);
        strike.setStruckAt(now);
        strike.setTxHash("0x" + "a".repeat(64));

        assertThat(strike.getId()).isNull(); // not persisted yet
        assertThat(strike.getAssetId()).isEqualTo(assetId);
        assertThat(strike.getStrikeId()).isEqualTo(1L);
        assertThat(strike.getNavPerShare()).isEqualByComparingTo("1.05");
        assertThat(strike.getEffectiveAt()).isEqualTo(now);
        assertThat(strike.getReportHash()).hasSize(3);
        assertThat(strike.getReportDocId()).isEqualTo(docId);
        assertThat(strike.getStruckBy()).isEqualTo(actorId);
        assertThat(strike.getStruckAt()).isEqualTo(now);
        assertThat(strike.getTxHash()).startsWith("0x");
    }

    @Test
    void vaultRequest_gettersAndSetters() {
        VaultRequest req = new VaultRequest();
        UUID assetId = UUID.randomUUID();
        Instant now = Instant.now();

        req.setAssetId(assetId);
        req.setRequestId(BigInteger.valueOf(42));
        req.setRequestType(VaultRequestType.DEPOSIT);
        req.setControllerAddr("0x" + "c".repeat(40));
        req.setOwnerAddr("0x" + "d".repeat(40));
        req.setAssetAmount(BigInteger.valueOf(10_000));
        req.setShareAmount(null);
        req.setRequestStatus(VaultRequestStatus.PENDING);
        req.setFulfilledAt(now);
        req.setFulfilledTx("0x" + "e".repeat(64));
        req.setNavAtFulfill(BigDecimal.valueOf(1.05));

        assertThat(req.getId()).isNull();
        assertThat(req.getAssetId()).isEqualTo(assetId);
        assertThat(req.getRequestId()).isEqualTo(BigInteger.valueOf(42));
        assertThat(req.getRequestType()).isEqualTo(VaultRequestType.DEPOSIT);
        assertThat(req.getControllerAddr()).startsWith("0x");
        assertThat(req.getOwnerAddr()).startsWith("0x");
        assertThat(req.getAssetAmount()).isEqualTo(BigInteger.valueOf(10_000));
        assertThat(req.getShareAmount()).isNull();
        assertThat(req.getRequestStatus()).isEqualTo(VaultRequestStatus.PENDING);
        assertThat(req.getRequestedAt()).isNotNull();
        assertThat(req.getFulfilledAt()).isEqualTo(now);
        assertThat(req.getFulfilledTx()).startsWith("0x");
        assertThat(req.getNavAtFulfill()).isEqualByComparingTo("1.05");
    }

    @Test
    void vaultRequestType_andStatus_enumValues() {
        assertThat(VaultRequestType.values()).containsExactlyInAnyOrder(VaultRequestType.DEPOSIT, VaultRequestType.REDEEM);
        assertThat(VaultRequestStatus.values()).containsExactlyInAnyOrder(
                VaultRequestStatus.PENDING, VaultRequestStatus.FULFILLED, VaultRequestStatus.CANCELLED);
    }

    @Test
    void assetSlot_gettersAndSetters() {
        AssetSlot slot = new AssetSlot();
        UUID assetId = UUID.randomUUID();
        slot.setAssetId(assetId);
        slot.setSlotId(BigInteger.ONE);
        slot.setName("Bond Series A");
        slot.setMetadata(Map.of("couponRate", "0.05", "maturity", "2030-12-31"));
        slot.setSupplyCap(BigInteger.valueOf(100_000));
        slot.setPaused(true);

        assertThat(slot.getId()).isNull();
        assertThat(slot.getAssetId()).isEqualTo(assetId);
        assertThat(slot.getSlotId()).isEqualTo(BigInteger.ONE);
        assertThat(slot.getName()).isEqualTo("Bond Series A");
        assertThat(slot.getMetadata()).containsKey("couponRate");
        assertThat(slot.getSupplyCap()).isEqualTo(BigInteger.valueOf(100_000));
        assertThat(slot.isPaused()).isTrue();
        assertThat(slot.getCreatedAt()).isNotNull();
    }

    @Test
    void assetTokenUnit_gettersAndSetters() {
        AssetTokenUnit unit = new AssetTokenUnit();
        UUID assetId = UUID.randomUUID();
        unit.setAssetId(assetId);
        unit.setSlotId(BigInteger.TWO);
        unit.setTokenId(BigInteger.valueOf(42));
        unit.setOwnerAddr("0x" + "a".repeat(40));
        unit.setTokenValue(BigInteger.valueOf(500_000));
        unit.setFrozen(true);
        unit.setFreezeReason("AML check");

        assertThat(unit.getId()).isNull();
        assertThat(unit.getAssetId()).isEqualTo(assetId);
        assertThat(unit.getSlotId()).isEqualTo(BigInteger.TWO);
        assertThat(unit.getTokenId()).isEqualTo(BigInteger.valueOf(42));
        assertThat(unit.getOwnerAddr()).startsWith("0x");
        assertThat(unit.getTokenValue()).isEqualTo(BigInteger.valueOf(500_000));
        assertThat(unit.isFrozen()).isTrue();
        assertThat(unit.getFreezeReason()).isEqualTo("AML check");
        assertThat(unit.getCreatedAt()).isNotNull();
        assertThat(unit.getUpdatedAt()).isNotNull();
    }

    @Test
    void assetCouponPayment_gettersAndSetters() {
        AssetCouponPayment payment = new AssetCouponPayment();
        UUID assetId = UUID.randomUUID();
        payment.setAssetId(assetId);
        payment.setSlotId(BigInteger.ONE);
        payment.setPeriodNo(3);
        payment.setScheduledDate(LocalDate.of(2026, 6, 1));
        payment.setPaidDate(LocalDate.of(2026, 6, 3));
        payment.setAmountPerUnit(BigDecimal.valueOf(25.00));
        payment.setCouponStatus(CouponStatus.PAID);
        payment.setTxRef("0x" + "b".repeat(64));

        assertThat(payment.getId()).isNull();
        assertThat(payment.getAssetId()).isEqualTo(assetId);
        assertThat(payment.getSlotId()).isEqualTo(BigInteger.ONE);
        assertThat(payment.getPeriodNo()).isEqualTo(3);
        assertThat(payment.getScheduledDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(payment.getPaidDate()).isEqualTo(LocalDate.of(2026, 6, 3));
        assertThat(payment.getAmountPerUnit()).isEqualByComparingTo("25.00");
        assertThat(payment.getCouponStatus()).isEqualTo(CouponStatus.PAID);
        assertThat(payment.getTxRef()).startsWith("0x");
        assertThat(payment.getCreatedAt()).isNotNull();
        assertThat(payment.getUpdatedAt()).isNotNull();
    }

    @Test
    void couponStatus_enumValues() {
        assertThat(CouponStatus.values()).containsExactlyInAnyOrder(
                CouponStatus.SCHEDULED, CouponStatus.PAID, CouponStatus.MISSED);
    }

    @Test
    void tokenStandard_newValues_accessible() {
        assertThat(TokenStandard.valueOf("ERC3525")).isEqualTo(TokenStandard.ERC3525);
        assertThat(TokenStandard.valueOf("ERC4626")).isEqualTo(TokenStandard.ERC4626);
        assertThat(TokenStandard.valueOf("ERC7540")).isEqualTo(TokenStandard.ERC7540);
        assertThat(TokenStandard.valueOf("STARKNET_ERC3525")).isEqualTo(TokenStandard.STARKNET_ERC3525);
        assertThat(TokenStandard.valueOf("DAML_BOND_FIXED")).isEqualTo(TokenStandard.DAML_BOND_FIXED);
        assertThat(TokenStandard.valueOf("DAML_BOND_FLOATING")).isEqualTo(TokenStandard.DAML_BOND_FLOATING);
        assertThat(TokenStandard.valueOf("DAML_BOND_ZERO")).isEqualTo(TokenStandard.DAML_BOND_ZERO);
        assertThat(TokenStandard.valueOf("SPL_2022_BOND")).isEqualTo(TokenStandard.SPL_2022_BOND);
        assertThat(TokenStandard.valueOf("SPL_2022_CONFIDENTIAL")).isEqualTo(TokenStandard.SPL_2022_CONFIDENTIAL);
    }
}
