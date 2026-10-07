package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent;
import de.makibytes.registerwerk.shared.RegisterNotReconciledException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 9A-05: a refused disclosure is counted (and the batch carries on), never silently swallowed. */
@DisplayName("Register statement refusal while the register is unreconciled")
class RegisterStatementRefusalTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RegisterStatementService service = mock(RegisterStatementService.class);

    private double refused() {
        var c = meters.find("registerwerk_register_statements_refused_total").tag("reason", "unreconciled").counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("the annual job counts every refused holder, keeps going and leaves lastStatementAt untouched")
    void annualJobCountsRefusals() {
        AssetHolderRepository holders = mock(AssetHolderRepository.class);
        AssetHolder a = new AssetHolder();
        a.setId(UUID.randomUUID());
        AssetHolder b = new AssetHolder();
        b.setId(UUID.randomUUID());
        when(holders.findAnnualStatementDueFirst(any(), any(Pageable.class))).thenReturn(List.of(a, b));
        UUID asset = UUID.randomUUID();
        when(service.issueForHolder(any(), any())).thenThrow(new RegisterNotReconciledException(asset, "refused"));

        new AnnualRegisterStatementJob(holders, service, meters, 200).issueAnnualStatements();

        assertThat(refused()).isEqualTo(2);
    }

    @Test
    @DisplayName("the event listener counts a refused event-driven statement instead of failing the register operation")
    void listenerCountsRefusals() {
        when(service.issueForHolder(any(), any(StatementTrigger.class)))
                .thenThrow(new RegisterNotReconciledException(UUID.randomUUID(), "refused"));

        new RegisterStatementEventListener(service, meters)
                .onHolderRegisterChanged(new HolderRegisterChangedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN"));

        assertThat(refused()).isEqualTo(1);
    }
}
