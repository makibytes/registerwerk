package de.makibytes.registerwerk.travelrule.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TravelRuleProductionReadinessCheckTest {

    @Test
    void productionRejectsNoopTransport() {
        TravelRuleProperties properties = new TravelRuleProperties();

        TravelRuleProductionReadinessCheck check =
                new TravelRuleProductionReadinessCheck(properties, "inbox-secret", true);

        assertThatThrownBy(check::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRP or NOTABENE");
    }

    @Test
    void productionRejectsTrpWithoutMutualTls() {
        TravelRuleProperties properties = new TravelRuleProperties();
        properties.setProtocol("TRP");
        properties.getTrp().setEndpoint("https://trp.example");

        TravelRuleProductionReadinessCheck check =
                new TravelRuleProductionReadinessCheck(properties, "inbox-secret", true);

        assertThatThrownBy(check::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mTLS");
    }

    @Test
    void productionRejectsDisabledInbox() {
        TravelRuleProperties properties = new TravelRuleProperties();
        properties.setProtocol("NOTABENE");
        properties.getNotabene().setApiKey("outbound-secret");
        properties.getNotabene().setVaspDid("did:example:registerwerk");

        TravelRuleProductionReadinessCheck check =
                new TravelRuleProductionReadinessCheck(properties, "", true);

        assertThatThrownBy(check::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INBOX_API_KEY");
    }
}
