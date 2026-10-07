package de.makibytes.registerwerk.travelrule.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TravelRuleProductionReadinessCheckTest {

    @Test
    void legacySharedKeyIsRefusedWhenProductionModeComesFromTheDashDProperty() {
        // Wave 5a: the check used @Value("${REGISTERWERK_PRODUCTION_MODE}"); -Dregisterwerk.production-mode=true never reached it
        TravelRuleProperties properties = new TravelRuleProperties();
        withOwnVasp(properties);
        properties.setProtocol("TRP");
        properties.setLegacySharedKey(true);
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty(de.makibytes.registerwerk.shared.ProductionMode.PROPERTY_NAME, "true");

        assertThatThrownBy(new TravelRuleProductionReadinessCheck(properties, "", env)::check)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRejectsNoopTransport() {
        TravelRuleProperties properties = new TravelRuleProperties();
        withOwnVasp(properties);

        TravelRuleProductionReadinessCheck check =
                new TravelRuleProductionReadinessCheck(properties, "inbox-secret", true);

        assertThatThrownBy(check::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRP or NOTABENE");
    }

    @Test
    void productionRejectsTrpWithoutMutualTls() {
        TravelRuleProperties properties = new TravelRuleProperties();
        withOwnVasp(properties);
        properties.setProtocol("TRP");
        properties.getTrp().setEndpoint("https://trp.example");

        TravelRuleProductionReadinessCheck check =
                new TravelRuleProductionReadinessCheck(properties, "inbox-secret", true);

        assertThatThrownBy(check::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mTLS");
    }

    @Test
    void productionRejectsMissingOwnVaspIdentity() {
        TravelRuleProperties properties = new TravelRuleProperties();
        properties.setProtocol("NOTABENE");
        properties.getNotabene().setApiKey("outbound-secret");
        properties.getNotabene().setVaspDid("did:example:registerwerk");

        TravelRuleProductionReadinessCheck check =
                new TravelRuleProductionReadinessCheck(properties, "", true);

        assertThatThrownBy(check::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OWN_VASP");
    }

    @Test
    void productionRejectsLegacySharedKey() {
        TravelRuleProperties properties = new TravelRuleProperties();
        withOwnVasp(properties);
        properties.setLegacySharedKey(true);

        assertThatThrownBy(new TravelRuleProductionReadinessCheck(properties, "", true)::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGACY_SHARED_KEY");
    }

    private static void withOwnVasp(TravelRuleProperties p) {
        p.getOwnVasp().setDid("did:example:registerwerk");
        p.getOwnVasp().setLegalName("Registerwerk Operator GmbH");
    }
}
