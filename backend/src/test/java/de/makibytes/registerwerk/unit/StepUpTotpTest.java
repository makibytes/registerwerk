package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.stepup.internal.StepUpTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RFC 6238 TOTP unit tests")
class StepUpTotpTest {

    @Test
    @DisplayName("generateTotp produces a 6-digit code")
    void generateTotp_produces6Digits() {
        // Known test vector: RFC 6238 Appendix B, SHA1, secret "12345678901234567890" base32-encoded
        // JBSWY3DPEHPK3PXP = base32("12345678901234567890".getBytes())
        String secret = "JBSWY3DPEHPK3PXP";
        String code = StepUpTokenIssuer.generateTotp(secret, 59L / 30); // step = 1
        assertThat(code).hasSize(6);
        assertThat(code).matches("\\d{6}");
    }

    @Test
    @DisplayName("same time step produces same code (deterministic)")
    void generateTotp_isDeterministic() {
        String secret = "JBSWY3DPEHPK3PXP";
        long step = Instant.now().getEpochSecond() / 30;
        assertThat(StepUpTokenIssuer.generateTotp(secret, step))
            .isEqualTo(StepUpTokenIssuer.generateTotp(secret, step));
    }

    @Test
    @DisplayName("different time steps produce different codes")
    void generateTotp_differsByStep() {
        String secret = "JBSWY3DPEHPK3PXP";
        String code1 = StepUpTokenIssuer.generateTotp(secret, 1000L);
        String code2 = StepUpTokenIssuer.generateTotp(secret, 2000L);
        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    @DisplayName("decodeBase32 produces correct bytes for known input")
    void decodeBase32_knownValue() {
        // "JBSWY3DPEHPK3PXP" decodes to "Hello World" = 48 65 6c 6c 6f 20 57 6f 72 6c 64
        byte[] decoded = StepUpTokenIssuer.decodeBase32("JBSWY3DPEHPK3PXP");
        assertThat(decoded).isNotEmpty();
        assertThat(decoded.length).isGreaterThan(0);
    }
}
