package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.stepup.api.DualControlTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("DualControlService - expected digest of the live request (K3, 6-08)")
class DualControlServiceDigestTest {

    private final DualControlProperties props = new DualControlProperties();
    private final DualControlService service = new DualControlService(null, null, props, null, null, null,
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/tokens/7/burn");
        r.setQueryString("b=2&a=1");
        if (body != null) {
            r.setAttribute(DualControlBodyCachingFilter.CACHED_BODY_ATTRIBUTE, body.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    @Test
    @DisplayName("matches what an approver's client computes from 'METHOD /path?query'")
    void matchesClientDigest() {
        assertThat(service.expectedDigest(request(null), "ANY"))
                .isEqualTo(DualControlTarget.digestOfTarget("POST /api/v1/tokens/7/burn?a=1&b=2", null));
    }

    @Test
    @DisplayName("a body-bound reason also covers the canonical body; a different amount gives a different digest")
    void bodyBoundReason() {
        props.setBindBodyReasons(List.of("BURN"));
        String five = service.expectedDigest(request("{\"amount\": 5, \"to\":\"x\"}"), "BURN");
        assertThat(five).isEqualTo(DualControlTarget.digestOfTarget("POST /api/v1/tokens/7/burn?a=1&b=2",
                DualControlTarget.canonicalJson("{\"to\":\"x\",\"amount\":5}")));
        assertThat(service.expectedDigest(request("{\"amount\": 500, \"to\":\"x\"}"), "BURN")).isNotEqualTo(five);
    }

    @Test
    @DisplayName("a body-bound reason fails closed when the body was not captured or is not JSON")
    void bodyUnavailable() {
        props.setBindBodyReasons(List.of("BURN"));
        assertThatThrownBy(() -> service.expectedDigest(request(null), "BURN")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.expectedDigest(request("{oops"), "BURN")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("rolling back target binding for a reason yields no expected digest")
    void rollbackSwitch() {
        props.setBindTargetReasons(List.of("OTHER"));
        assertThat(service.expectedDigest(request(null), "ANY")).isNull();
        assertThat(service.expectedDigest(request(null), "OTHER")).isNotNull();
    }
}
