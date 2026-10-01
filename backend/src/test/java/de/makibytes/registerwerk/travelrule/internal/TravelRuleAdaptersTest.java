package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort.VaspInfo;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 6-26: the adapters use the resolved beneficiary VASP and the real transfer details. */
class TravelRuleAdaptersTest {

    @Test
    void trpUsesDirectoryEndpointNotTheStaticOne() {
        TravelRuleProperties.Trp trp = new TravelRuleProperties.Trp();
        trp.setEndpoint("https://static.example/trp");
        trp.setAllowedHosts(List.of("vasp.example.org", "static.example"));
        trp.setAllowInsecureEndpoints(true); // skip DNS resolution in a unit test

        assertThat(TrpAdapter.deliveryEndpoint(
                new VaspInfo("did:x", "X", "DE", "https://vasp.example.org/trp"), trp))
                .isEqualTo("https://vasp.example.org/trp");
        assertThat(TrpAdapter.deliveryEndpoint(new VaspInfo("did:x", "X", "DE", ""), trp))
                .isEqualTo("https://static.example/trp");
    }

    @Test
    void trpRefusesInsecureOrOffListEndpoints() {
        TravelRuleProperties.Trp trp = new TravelRuleProperties.Trp();
        trp.setAllowedHosts(List.of("vasp.example.org"));
        assertThatThrownBy(() -> TrpAdapter.deliveryEndpoint(new VaspInfo("d", "n", "DE", "http://vasp.example.org/x"), trp))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> TrpAdapter.deliveryEndpoint(new VaspInfo("d", "n", "DE", "https://evil.example/x"), trp))
                .hasMessageContaining("allow-list");
        TravelRuleProperties.Trp open = new TravelRuleProperties.Trp();
        assertThatThrownBy(() -> TrpAdapter.deliveryEndpoint(new VaspInfo("d", "n", "DE", "https://127.0.0.1/x"), open))
                .hasMessageContaining("non-public");
        assertThatThrownBy(() -> TrpAdapter.deliveryEndpoint(new VaspInfo("d", "n", "DE", null), open))
                .hasMessageContaining("No TRP delivery endpoint");
    }

    @Test
    void notabeneBodyCarriesRealAssetAmountAndBeneficiaryDid() {
        TravelRuleProperties props = new TravelRuleProperties();
        props.getNotabene().setApiKey("k");
        props.getNotabene().setVaspDid("did:own");
        NotabeneAdapter adapter = new NotabeneAdapter(props, new ObjectMapper(), RestClient.builder());
        Ivms101.TravelRuleMessage msg = new Ivms101.TravelRuleMessage(null, null, null, null,
                new Ivms101.TransferDetails("tx", "2026-09-01", "42", "BND", "ON_CHAIN", "0xtoken"));

        Map<String, Object> body = adapter.buildTransactionPayload(UUID.randomUUID(), msg,
                new VaspInfo("did:beneficiary", "B", "DE", "https://b.example"));

        assertThat(body).containsEntry("transactionAsset", "BND").containsEntry("transactionAmount", "42")
                .containsEntry("beneficiaryDid", "did:beneficiary").containsEntry("assetIdentifier", "0xtoken");
        assertThatThrownBy(() -> adapter.buildTransactionPayload(UUID.randomUUID(),
                new Ivms101.TravelRuleMessage(null, null, null, null, null), null))
                .isInstanceOf(IllegalStateException.class);
    }
}
