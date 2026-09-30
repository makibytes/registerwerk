package de.makibytes.registerwerk.shared.api;

import de.makibytes.registerwerk.blockchain.web.dto.MintRequest;
import de.makibytes.registerwerk.erc3643.web.dto.Erc3643AgentRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StrictAmount - decimal-string amounts, no silent precision loss (P4B-7)")
class StrictAmountTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private MintRequest mint(String amountJson) throws Exception {
        return mapper.readValue("{\"toAddress\":\"0xabc\",\"amount\":" + amountJson + "}", MintRequest.class);
    }

    @Test
    @DisplayName("a decimal string above 2^53 (1e21 base units) is parsed exactly")
    void hugeString_isExact() throws Exception {
        assertThat(mint("\"1000000000000000000000\"").amount()).isEqualTo(new BigInteger("1000000000000000000000"));
    }

    @Test
    @DisplayName("a JSON number of 1e21 (already rounded by a JS client) is rejected instead of mis-parsed")
    void hugeNumber_isRejected() {
        assertThatThrownBy(() -> mint("1000000000000000000000"))
                .hasMessageContaining(StrictAmount.MESSAGE_PREFIX + "JSON numbers of 2^53 or more");
        assertThatThrownBy(() -> mint("1e21")).hasMessageContaining(StrictAmount.MESSAGE_PREFIX);
        assertThatThrownBy(() -> mint("9007199254740993")).hasMessageContaining(StrictAmount.MESSAGE_PREFIX);
    }

    @Test
    @DisplayName("a small integer JSON number is still accepted for one release (deprecated)")
    void smallNumber_isAccepted() throws Exception {
        assertThat(mint("1500").amount()).isEqualTo(BigInteger.valueOf(1500));
        assertThat(mint("9007199254740991").amount()).isEqualTo(BigInteger.valueOf(9007199254740991L));
    }

    @Test
    @DisplayName("malformed strings are rejected")
    void malformedStrings_areRejected() {
        for (String bad : new String[] {"\"1e5\"", "\"1.5\"", "\"1,000\"", "\"0x10\"", "\"\"", "\"abc\"", "1.5"}) {
            assertThatThrownBy(() -> mint(bad)).as(bad).hasMessageContaining(StrictAmount.MESSAGE_PREFIX);
        }
    }

    @Test
    @DisplayName("BigDecimal fields: strings exact, numbers only up to 15 significant digits, lists too")
    void bigDecimal_rules() throws Exception {
        String base = "{\"from\":\"0xabc\",\"legalBasis\":\"x\",\"amount\":";
        var mint = mapper.readValue(base + "\"123456789012345678.123456789\"}", Erc3643AgentRequests.ForceBurn.class);
        assertThat(mint.amount()).isEqualByComparingTo(new BigDecimal("123456789012345678.123456789"));
        assertThat(mapper.readValue(base + "0.5}", Erc3643AgentRequests.ForceBurn.class).amount())
                .isEqualByComparingTo("0.5");
        assertThatThrownBy(() -> mapper.readValue(base + "123456789012345678.123456789}", Erc3643AgentRequests.ForceBurn.class))
                .hasMessageContaining(StrictAmount.MESSAGE_PREFIX);
    }
}
