package de.makibytes.registerwerk.payment.api;

import de.makibytes.registerwerk.marketplace.api.DappPaymentMethod;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.PaymentMethodResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Investor-facing payment method carries the attestation status, not an unconditional fact (5A-11)")
class PaymentMethodResponseAttestationTest {

    private static final UUID CHAIN = UUID.randomUUID();
    private static final String TOKEN = "0x" + "aa".repeat(20);

    private PaymentRail emtRail() {
        PaymentRail r = new PaymentRail();
        r.setCode("aueur");
        r.setDisplayName("AllUnity");
        r.setRailType(PaymentRailType.STABLECOIN);
        r.setCurrency("EUR");
        r.setDecimals(6);
        r.setEmtFlag(true);
        r.setRedemptionAtPar(true);
        r.setMicarAuthorization("BaFin EMI");
        r.setEnabled(true);
        return r;
    }

    @Test
    void unverifiedRailIsMarkedUnverifiedWithoutDate() {
        PaymentMethodResponse dto = PaymentMethodResponse.forRail(new DappPaymentMethod(), emtRail(), Map.of(CHAIN, TOKEN));
        assertThat(dto.attestationStatus()).isEqualTo("UNVERIFIED");
        assertThat(dto.micarVerifiedAt()).isNull();
        assertThat(dto.attestationNotice()).isEqualTo(PaymentMethodResponse.NOTICE_UNVERIFIED);
        assertThat(dto.micarAuthorization()).isEqualTo("BaFin EMI");
    }

    @Test
    void attestedRailCarriesDateAndQualifiedNotice_untilTheTokenChanges() {
        PaymentRail r = emtRail();
        r.setMicarVerified(true);
        r.setMicarVerifiedAt(Instant.parse("2026-09-01T10:00:00Z"));
        r.setMicarAttestedFingerprint(PaymentRailAttestation.fingerprint(r, Map.of(CHAIN, TOKEN)));

        PaymentMethodResponse ok = PaymentMethodResponse.forRail(new DappPaymentMethod(), r, Map.of(CHAIN, TOKEN));
        assertThat(ok.attestationStatus()).isEqualTo("OPERATOR_ATTESTED");
        assertThat(ok.micarVerifiedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(ok.attestationNotice()).contains("not independently verified");

        PaymentMethodResponse swapped = PaymentMethodResponse.forRail(new DappPaymentMethod(), r,
                Map.of(CHAIN, "0x" + "bb".repeat(20)));
        assertThat(swapped.attestationStatus()).isEqualTo("UNVERIFIED");
        assertThat(swapped.micarVerifiedAt()).isNull();
    }

    @Test
    void disabledReasonIsExposedForTheBanner() {
        PaymentRail r = emtRail();
        r.setEnabled(false);
        r.setDisabledReason("MICAR_ATTESTATION_INVALIDATED");
        PaymentMethodResponse dto = PaymentMethodResponse.forRail(new DappPaymentMethod(), r, Map.of());
        assertThat(dto.railEnabled()).isFalse();
        assertThat(dto.railDisabledReason()).isEqualTo("MICAR_ATTESTATION_INVALIDATED");
    }
}
