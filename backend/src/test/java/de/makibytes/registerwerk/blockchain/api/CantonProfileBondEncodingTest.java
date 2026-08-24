package de.makibytes.registerwerk.blockchain.api;

import com.daml.ledger.javaapi.data.Bool;
import com.daml.ledger.javaapi.data.DamlEnum;
import com.daml.ledger.javaapi.data.DamlList;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.Date;
import com.daml.ledger.javaapi.data.Numeric;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CantonProfileBondEncodingTest {

    @Test
    void fixedBondMatchesTemplateIdentifierAndFieldOrder() {
        UUID assetId = UUID.randomUUID();
        AssetBondTerms terms = terms(assetId);

        DamlRecord encoded = CantonBondService.bondRecord(
                assetId,
                "Issuer::1220abc",
                terms,
                new DamlRecord.Field("couponRate", new Numeric(new BigDecimal("0.042"))));

        assertThat(CantonBondService.id("FixedRateBond").getPackageId())
                .isEqualTo("#registerwerk-canton");
        assertThat(CantonBondService.id("FixedRateBond").getModuleName())
                .isEqualTo("Registerwerk.Bond.FixedBond");
        assertThat(encoded.getFields()).extracting(field -> field.getLabel().orElseThrow())
                .containsExactly("assetId", "registryAdmin", "issuer", "regulatorObserver",
                        "terms", "couponRate", "status");
    }

    @Test
    void nestedTermsUseDamlDatesEnumsBoolAndCallEntryList() {
        UUID assetId = UUID.randomUUID();
        DamlRecord encoded = CantonBondService.bondRecord(
                assetId,
                "Issuer::1220abc",
                terms(assetId),
                new DamlRecord.Field("couponRate", new Numeric(new BigDecimal("0.042"))));

        DamlRecord nested = (DamlRecord) encoded.getFieldsMap().get("terms");
        assertThat(nested.getFields()).extracting(field -> field.getLabel().orElseThrow())
                .containsExactly("assetId", "faceValue", "currencyIso", "issueDate",
                        "maturityDate", "dayCount", "paymentFrequency", "callable", "callSchedule");
        assertThat(nested.getFieldsMap().get("issueDate")).isInstanceOf(Date.class);
        assertThat(((DamlEnum) nested.getFieldsMap().get("dayCount")).getConstructor())
                .isEqualTo("ActActIcma");
        assertThat(((DamlEnum) nested.getFieldsMap().get("paymentFrequency")).getConstructor())
                .isEqualTo("Annual");
        assertThat(((Bool) nested.getFieldsMap().get("callable")).getValue()).isTrue();
        assertThat(((DamlList) nested.getFieldsMap().get("callSchedule")).toList(v -> v))
                .singleElement()
                .isInstanceOf(DamlRecord.class);
    }

    private static AssetBondTerms terms(UUID assetId) {
        AssetBondTerms terms = new AssetBondTerms();
        terms.setAssetId(assetId);
        terms.setFaceValue(new BigDecimal("5000"));
        terms.setCurrencyIso("EUR");
        terms.setIssueDate(LocalDate.of(2023, 6, 30));
        terms.setMaturityDate(LocalDate.of(2033, 6, 30));
        terms.setDayCount(DayCountConvention.ACT_ACT_ICMA);
        terms.setPaymentFrequency(PaymentFrequency.ANNUAL);
        terms.setCallable(true);
        terms.setCallSchedule(List.of(Map.of(
                "callDate", "2028-06-30",
                "callPrice", new BigDecimal("101.00"))));
        return terms;
    }
}
