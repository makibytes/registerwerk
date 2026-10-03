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
    private final DualControlService service = new DualControlService(null, null, props, null, null, null, null,
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
    @DisplayName("matches what an approver's client computes from 'METHOD /path?query' plus the (here empty) canonical body")
    void matchesClientDigest() {
        assertThat(service.expectedDigest(request(""), "ANY"))
                .isEqualTo(DualControlTarget.digestOfTarget("POST /api/v1/tokens/7/burn?a=1&b=2", ""));
    }

    @Test
    @DisplayName("C2: every reason is body-bound by default; a different amount gives a different digest")
    void bodyBoundByDefault() {
        String five = service.expectedDigest(request("{\"amount\": 5, \"to\":\"x\"}"), "BURN");
        assertThat(five).isEqualTo(DualControlTarget.digestOfTarget("POST /api/v1/tokens/7/burn?a=1&b=2",
                DualControlTarget.canonicalJson("{\"to\":\"x\",\"amount\":5}")));
        assertThat(service.expectedDigest(request("{\"amount\": 500, \"to\":\"x\"}"), "BURN")).isNotEqualTo(five);
        assertThat(service.expectedDigest(request("{\"amount\": 5, \"to\":\"x\"}"), "SOMETHING_ELSE")).isEqualTo(five);
    }

    @Test
    @DisplayName("C2: the explicit opt-out leaves the body out (method, path and query stay bound)")
    void optOutReasonIgnoresBody() {
        props.setBodyOptOutReasons(List.of("UPLOAD"));
        assertThat(service.expectedDigest(request("--multipart--"), "UPLOAD"))
                .isEqualTo(DualControlTarget.digestOfTarget("POST /api/v1/tokens/7/burn?a=1&b=2", null));
    }

    @Test
    @DisplayName("C2: a request without a body (GET) binds the empty body instead of failing for want of a captured one")
    void methodWithoutBodyBindsEmptyBody() {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/wallets/7/export");
        assertThat(service.expectedDigest(get, "WALLET_KEYSTORE_EXPORT"))
                .isEqualTo(DualControlTarget.digestOfTarget("GET /api/v1/wallets/7/export", ""));
    }

    @Test
    @DisplayName("C2: an ambiguous request - repeated JSON key, repeated query parameter - cannot be bound and is refused")
    void ambiguousRequestsAreRefused() {
        assertThatThrownBy(() -> service.expectedDigest(request("{\"amount\":1,\"amount\":2}"), "BURN"))
                .isInstanceOf(AccessDeniedException.class);
        MockHttpServletRequest twice = request("{}");
        twice.setQueryString("a=1&a=2");
        assertThatThrownBy(() -> service.expectedDigest(twice, "BURN")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a body-bound reason fails closed when the body was not captured or is not JSON")
    void bodyUnavailable() {
        assertThatThrownBy(() -> service.expectedDigest(request(null), "BURN")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.expectedDigest(request("{oops"), "BURN")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("rolling back target binding for a reason yields no expected digest")
    void rollbackSwitch() {
        props.setBindTargetReasons(List.of("OTHER"));
        assertThat(service.expectedDigest(request(""), "ANY")).isNull();
        assertThat(service.expectedDigest(request(""), "OTHER")).isNotNull();
    }
}
