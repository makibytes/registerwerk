package de.makibytes.registerwerk.chain.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CantonProfileClientFactoryTest {

    @Test
    void parsesTlsEndpointAndDefaultsToTlsPort() {
        assertThat(CantonClientFactory.Endpoint.parse("grpcs://participant.example.com"))
                .isEqualTo(new CantonClientFactory.Endpoint("participant.example.com", 443, true));
        assertThat(CantonClientFactory.Endpoint.parse("https://participant.example.com:8443"))
                .isEqualTo(new CantonClientFactory.Endpoint("participant.example.com", 8443, true));
    }

    @Test
    void keepsPlaintextAnExplicitDevelopmentChoiceAndSupportsIpv6() {
        assertThat(CantonClientFactory.Endpoint.parse("grpc://participant.internal:5001"))
                .isEqualTo(new CantonClientFactory.Endpoint("participant.internal", 5001, false));
        assertThat(CantonClientFactory.Endpoint.parse("grpc://[2001:db8::1]:5002"))
                .isEqualTo(new CantonClientFactory.Endpoint("[2001:db8::1]", 5002, false));
    }

    @Test
    void preservesLegacyHostPortAsPlaintextButRejectsAmbiguousUrls() {
        assertThat(CantonClientFactory.Endpoint.parse("participant.internal:6001"))
                .isEqualTo(new CantonClientFactory.Endpoint("participant.internal", 6001, false));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CantonClientFactory.Endpoint.parse("http://participant:5001"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CantonClientFactory.Endpoint.parse("grpcs://user@participant:5001"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CantonClientFactory.Endpoint.parse("grpcs://participant:5001/api"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CantonClientFactory.Endpoint.parse(" "));
    }
}
