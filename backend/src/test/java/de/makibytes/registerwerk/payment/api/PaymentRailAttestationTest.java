package de.makibytes.registerwerk.payment.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment-rail MiCAR attestation binding (5A-11)")
class PaymentRailAttestationTest {

    private static final UUID CHAIN = UUID.randomUUID();
    private static final String TOKEN = "0x" + "aa".repeat(20);

    private PaymentRail rail() {
        PaymentRail r = new PaymentRail();
        r.setCode("aueur");
        r.setRailType(PaymentRailType.STABLECOIN);
        r.setCurrency("EUR");
        r.setDecimals(6);
        r.setIssuerName("Issuer");
        r.setIssuerLei("LEI");
        r.setMicarAuthorization("Auth");
        r.setEmtFlag(true);
        r.setWhitePaperUrl("https://x.example/wp");
        r.setRedemptionAtPar(true);
        return r;
    }

    @Test
    @DisplayName("fingerprint is stable, case-insensitive on addresses and order-independent")
    void stable() {
        UUID chain2 = UUID.randomUUID();
        String a = PaymentRailAttestation.fingerprint(rail(), Map.of(CHAIN, TOKEN, chain2, TOKEN));
        String b = PaymentRailAttestation.fingerprint(rail(), Map.of(chain2, TOKEN.toUpperCase().replace("0X", "0x"), CHAIN, TOKEN));
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    @DisplayName("every attested fact and the token address change the fingerprint")
    void everyFactMatters() {
        String base = PaymentRailAttestation.fingerprint(rail(), Map.of(CHAIN, TOKEN));
        java.util.List<java.util.function.Consumer<PaymentRail>> mutations = java.util.List.of(
                r -> r.setCode("x"), r -> r.setRailType(PaymentRailType.ERC7573_DVP), r -> r.setCurrency("USD"),
                r -> r.setDecimals(18), r -> r.setIssuerName("Other"), r -> r.setIssuerLei("L2"),
                r -> r.setMicarAuthorization("A2"), r -> r.setEmtFlag(false), r -> r.setWhitePaperUrl("https://y"),
                r -> r.setRedemptionAtPar(false));
        for (var m : mutations) {
            PaymentRail r = rail();
            m.accept(r);
            assertThat(PaymentRailAttestation.fingerprint(r, Map.of(CHAIN, TOKEN))).isNotEqualTo(base);
        }
        assertThat(PaymentRailAttestation.fingerprint(rail(), Map.of(CHAIN, "0x" + "bb".repeat(20)))).isNotEqualTo(base);
        assertThat(PaymentRailAttestation.fingerprint(rail(), Map.of())).isNotEqualTo(base);
    }

    @Test
    @DisplayName("field separators cannot be smuggled to forge a collision")
    void escapesSeparator() {
        PaymentRail a = rail();
        a.setIssuerName("A|B");
        a.setIssuerLei("C");
        PaymentRail b = rail();
        b.setIssuerName("A");
        b.setIssuerLei("B|C");
        assertThat(PaymentRailAttestation.fingerprint(a, Map.of())).isNotEqualTo(PaymentRailAttestation.fingerprint(b, Map.of()));
    }

    @Test
    @DisplayName("isEffective needs the flag AND a matching fingerprint (legacy flag-only rows are void)")
    void effective() {
        PaymentRail r = rail();
        r.setMicarVerified(true);
        assertThat(PaymentRailAttestation.isEffective(r, Map.of(CHAIN, TOKEN))).isFalse();
        r.setMicarAttestedFingerprint(PaymentRailAttestation.fingerprint(r, Map.of(CHAIN, TOKEN)));
        assertThat(PaymentRailAttestation.isEffective(r, Map.of(CHAIN, TOKEN))).isTrue();
        assertThat(PaymentRailAttestation.isEffective(r, Map.of(CHAIN, "0x" + "cc".repeat(20)))).isFalse();
        r.setMicarVerified(false);
        assertThat(PaymentRailAttestation.isEffective(r, Map.of(CHAIN, TOKEN))).isFalse();
    }
}
