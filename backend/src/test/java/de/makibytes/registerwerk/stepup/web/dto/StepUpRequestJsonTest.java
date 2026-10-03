package de.makibytes.registerwerk.stepup.web.dto;

import de.makibytes.registerwerk.stepup.api.DualControlTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The approver's {@code targetBody} is read exactly (C2), through the same mapper configuration as the web layer. */
@DisplayName("StepUpRequest - targetBody is parsed exactly")
class StepUpRequestJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static String request(String targetBodyJson) {
        return "{\"code\":\"123456\",\"method\":\"TOTP\",\"action\":\"A\",\"target\":\"POST /api/v1/x\","
                + "\"targetBody\":" + targetBodyJson + ",\"unrelated\":1}";
    }

    @Test
    @DisplayName("decimals keep every digit and the sibling fields still bind")
    void exactDecimals() {
        StepUpRequest r = mapper.readValue(request("{\"amount\":12345678901234567890.123456789012345678,\"to\":\"0xA\"}"),
                StepUpRequest.class);
        assertThat(r.code()).isEqualTo("123456");
        assertThat(DualControlTarget.canonicalJson(r.targetBody()))
                .isEqualTo("{\"amount\":12345678901234567890.123456789012345678,\"to\":\"0xA\"}");
    }

    @Test
    @DisplayName("a repeated key inside targetBody is refused")
    void duplicateKeysRefused() {
        assertThatThrownBy(() -> mapper.readValue(request("{\"amount\":\"1\",\"amount\":\"2\"}"), StepUpRequest.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an absent targetBody stays null")
    void absentBody() {
        StepUpRequest r = mapper.readValue("{\"code\":\"123456\"}", StepUpRequest.class);
        assertThat(r.targetBody()).isNull();
    }
}
