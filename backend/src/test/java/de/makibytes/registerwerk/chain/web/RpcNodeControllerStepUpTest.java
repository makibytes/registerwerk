package de.makibytes.registerwerk.chain.web;

import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RpcNodeController: every node change needs step-up + second approver (P4C-1)")
class RpcNodeControllerStepUpTest {

    private static final Set<String> CHANGING = Set.of(
            "addNode", "updateNode", "enable", "disable", "setExclusive", "delete", "resetGenesisPin");

    @Test
    void everyChangingEndpointRequiresSecondApprover() {
        int seen = 0;
        for (Method m : RpcNodeController.class.getDeclaredMethods()) {
            if (!CHANGING.contains(m.getName())) continue;
            seen++;
            RequiresStepUp step = m.getAnnotation(RequiresStepUp.class);
            assertThat(step).as(m.getName()).isNotNull();
            assertThat(step.requireSecondApprover()).as(m.getName()).isTrue();
            assertThat(step.reason()).isEqualTo("RPC_NODE_CHANGE");
        }
        assertThat(seen).isEqualTo(CHANGING.size());
    }

    @Test
    void everyStateChangingHttpMethodIsCovered() {
        for (Method m : RpcNodeController.class.getDeclaredMethods()) {
            boolean mutating = m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class)
                    || m.isAnnotationPresent(DeleteMapping.class);
            // redetect / console-token are detection-state operations, not routing governance
            if (mutating && !Set.of("redetect", "mintConsoleToken").contains(m.getName())) {
                assertThat(m.getAnnotation(RequiresStepUp.class)).as(m.getName()).isNotNull();
            }
        }
    }
}
