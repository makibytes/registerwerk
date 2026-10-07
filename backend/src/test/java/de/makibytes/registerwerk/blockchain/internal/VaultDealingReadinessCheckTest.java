package de.makibytes.registerwerk.blockchain.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.makibytes.registerwerk.blockchain.api.Erc7540AdminPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("VaultDealingReadinessCheck — production warning for vaults without a dealing cut-off (T1-07)")
class VaultDealingReadinessCheckTest {

    private final Erc7540AdminPort vaults = mock(Erc7540AdminPort.class);

    private List<ILoggingEvent> run(boolean production) {
        MockEnvironment env = new MockEnvironment();
        if (production) env.setProperty("registerwerk.production-mode", "true");
        VaultDealingReadinessCheck check = new VaultDealingReadinessCheck(vaults,
                new VaultDealingSettings(env, "17:00", 86_400L));
        Logger logger = (Logger) LoggerFactory.getLogger(VaultDealingReadinessCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            check.warnAboutVaultsWithoutDealingCutoff();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @Test
    @DisplayName("production: every confirmed vault without a dealing cut-off is listed in one loud warning")
    void productionListsTheVaults() {
        when(vaults.listVaultsWithoutDealingCutoff()).thenReturn(List.of(
                "Aurora Fund (0x01) on ETHEREUM/TESTNET — no dealing cut-off",
                "Boreal Fund (0x02) on BASE/MAINNET — dealing cut-off unreadable"));

        List<ILoggingEvent> events = run(true);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.get(0).getFormattedMessage())
                .contains("Aurora Fund").contains("Boreal Fund").contains("dealing cut-off");
    }

    @Test
    @DisplayName("production with every vault configured logs nothing")
    void productionAllConfigured_isQuiet() {
        when(vaults.listVaultsWithoutDealingCutoff()).thenReturn(List.of());

        assertThat(run(true)).isEmpty();
    }

    @Test
    @DisplayName("demo mode never reads the chain and never warns")
    void demoMode_isUntouched() {
        assertThat(run(false)).isEmpty();
        verify(vaults, never()).listVaultsWithoutDealingCutoff();
    }

    @Test
    @DisplayName("a failing read never breaks startup")
    void failure_isSwallowed() {
        when(vaults.listVaultsWithoutDealingCutoff()).thenThrow(new IllegalStateException("db down"));

        assertThat(run(true)).extracting(ILoggingEvent::getLevel).doesNotContain(Level.TRACE);
    }
}
