package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3-23 regression: {@code @RequestAttribute(DUAL_CONTROL_APPROVER_ID)} was always null because the
 * attribute was only set by the {@code @RequiresStepUp} aspect, i.e. after argument resolution. The
 * interceptor sets it in {@code preHandle}. (Standalone MockMvc has no AOP, which isolates exactly
 * the argument-resolution ordering.)
 */
class DualControlApproverInterceptorTest {

    private final StepUpTokenValidator validator = mock(StepUpTokenValidator.class);
    private final UUID approver = UUID.randomUUID();
    private final MockMvc withInterceptor = MockMvcBuilders.standaloneSetup(new Ctl())
            .addInterceptors(new DualControlApproverInterceptor(validator)).build();
    private final MockMvc withoutInterceptor = MockMvcBuilders.standaloneSetup(new Ctl()).build();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @RestController
    static class Ctl {
        @PostMapping("/api/v1/dual")
        @RequiresStepUp(requireSecondApprover = true, reason = "TEST_ACTION")
        String dual(@RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
            return String.valueOf(approverId);
        }

        @PostMapping("/api/v1/single")
        @RequiresStepUp(reason = "TEST_SINGLE")
        String single(@RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
            return String.valueOf(approverId);
        }
    }

    private void authenticate() {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "HS256"),
                Map.of("sub", "initiator"));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null, "ROLE_REGISTRY_ADMIN"));
    }

    @Test
    void controllerReceivesTheValidatedApprover() throws Exception {
        authenticate();
        when(validator.validateDualControlToken(eq("tok"), eq("initiator"), eq("TEST_ACTION"))).thenReturn(approver);

        withInterceptor.perform(post("/api/v1/dual").header("X-Dual-Control-Token", "tok")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string(approver.toString()));
    }

    @Test
    void withoutTheInterceptorTheAttributeIsNull() throws Exception {
        authenticate();
        withoutInterceptor.perform(post("/api/v1/dual").header("X-Dual-Control-Token", "tok"))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
    }

    @Test
    void invalidTokenIsLeftToTheAspectAndNeverSetsTheAttribute() throws Exception {
        authenticate();
        when(validator.validateDualControlToken(any(), any(), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("bad"));

        withInterceptor.perform(post("/api/v1/dual").header("X-Dual-Control-Token", "bad"))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
    }

    @Test
    void singleApproverEndpointsAreNotValidatedAgain() throws Exception {
        authenticate();
        withInterceptor.perform(post("/api/v1/single").header("X-Dual-Control-Token", "tok"))
                .andExpect(content().string("null"));
        verify(validator, never()).validateDualControlToken(any(), any(), any());
    }
}
