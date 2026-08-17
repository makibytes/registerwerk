package de.makibytes.registerwerk.notification.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.makibytes.registerwerk.blockchain.events.ConfidentialReconciliationCompletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for finding #11 (Phase 9): a confidential reconciliation mismatch must be logged at
 * ERROR (this repo's established operational-alert pattern — see the listener's class doc), and
 * a clean run must not be.
 */
class ConfidentialReconciliationAlertListenerTest {

    private ConfidentialReconciliationAlertListener listener;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logbackLogger;

    @BeforeEach
    void setUp() {
        listener = new ConfidentialReconciliationAlertListener();
        logbackLogger = (Logger) LoggerFactory.getLogger(ConfidentialReconciliationAlertListener.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logbackLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("logs at ERROR when a mismatch is present")
    void logsError_onMismatch() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();

        listener.on(new ConfidentialReconciliationCompletedEvent(
                assetId, deploymentId, UUID.randomUUID(), "SYSTEM", 5, 2, Map.of("mismatches", "details")));

        assertThat(logAppender.list).hasSize(1);
        ILoggingEvent event = logAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .contains("MISMATCH")
                .contains(assetId.toString())
                .contains(deploymentId.toString());
    }

    @Test
    @DisplayName("does not log when the run was clean (mismatchCount == 0)")
    void doesNotLog_onCleanRun() {
        listener.on(new ConfidentialReconciliationCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SYSTEM", 5, 0, Map.of()));

        assertThat(logAppender.list).isEmpty();
    }
}
