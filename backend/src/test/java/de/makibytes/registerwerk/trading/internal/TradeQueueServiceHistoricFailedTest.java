package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionNoteRepository;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.web.TradingAdminController;
import de.makibytes.registerwerk.trading.web.dto.UnresolvedTradeResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TradeQueueService.listHistoricFailed (8A-06)")
class TradeQueueServiceHistoricFailedTest {

    private final TradeExecutionRepository executions = mock(TradeExecutionRepository.class);
    private final TradeExecutionNoteRepository notes = mock(TradeExecutionNoteRepository.class);
    private final TradeQueueService service = new TradeQueueService(
            executions, notes, new TradingProperties(), mock(ApplicationEventPublisher.class), new SimpleMeterRegistry());

    @Test
    @DisplayName("returns the FAILED-after-declared-payment trades read-only, with payment reference and notes")
    void listsHistoricFailedTrades() {
        TradeExecution failed = new TradeExecution();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(failed, "id", id);
        failed.setSettlementStatus(SettlementStatus.FAILED);
        failed.setExecutedQuantity(BigDecimal.TEN);
        failed.setPaymentReference("REF-1");
        failed.setPaymentDeclaredAt(Instant.now());
        when(executions.findFailedAfterDeclaredPayment()).thenReturn(List.of(failed));
        when(notes.findByExecutionIdOrderByCreatedAtAsc(id)).thenReturn(List.of());

        List<UnresolvedTradeResponse> result = service.listHistoricFailed();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).trade().id()).isEqualTo(id);
        assertThat(result.get(0).trade().settlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(result.get(0).aged()).isFalse();
        verify(executions, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a plain FAILED trade (no declared payment) is not returned: only the dedicated query is used")
    void emptyWhenNoneQualify() {
        when(executions.findFailedAfterDeclaredPayment()).thenReturn(List.of());
        assertThat(service.listHistoricFailed()).isEmpty();
        verify(executions, never()).findBySettlementStatusOrderByUnresolvedAtAsc(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the endpoint is mapped under the REGISTRY_ADMIN-guarded admin controller")
    void endpointIsAdminGuarded() throws Exception {
        PreAuthorize guard = TradingAdminController.class.getAnnotation(PreAuthorize.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("hasRole('REGISTRY_ADMIN')");
        Method m = TradingAdminController.class.getMethod("historicFailed");
        assertThat(m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())
                .containsExactly("/historic-failed");
    }
}
