package de.makibytes.registerwerk.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetentionSweepJob unit tests")
class RetentionSweepJobTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-15T00:00:00Z");

    @Mock
    private JdbcTemplate jdbc;

    private RetentionProperties properties;
    private RetentionSweepJob job;

    private void init() {
        properties = new RetentionProperties();
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        job = new RetentionSweepJob(properties, jdbc, clock);
    }

    @Test
    @DisplayName("a disabled target is not touched at all")
    void disabledTargetSkipsEntirely() {
        init();
        properties.getLoginAttempt().setEnabled(false);

        job.sweepLoginAttempt();

        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("login_attempt sweep loops in batches until a partial batch is deleted")
    void loginAttemptSweepsInBatches() {
        init();
        properties.getLoginAttempt().setBatchSize(2);
        // First call deletes a full batch (2) -> loop continues; second call deletes fewer (1)
        // than the batch size -> loop stops.
        when(jdbc.update(anyString(), any(Timestamp.class), any(Integer.class)))
                .thenReturn(2)
                .thenReturn(1);

        job.sweepLoginAttempt();

        verify(jdbc, times(2)).update(anyString(), any(Timestamp.class), any(Integer.class));
    }

    @Test
    @DisplayName("login_attempt cutoff is computed from the injected Clock and the configured max age")
    void loginAttemptCutoffUsesConfiguredMaxAge() {
        init();
        when(jdbc.update(anyString(), any(Timestamp.class), any(Integer.class))).thenReturn(0);

        job.sweepLoginAttempt();

        ArgumentCaptor<Timestamp> cutoffCaptor = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc).update(anyString(), cutoffCaptor.capture(), any(Integer.class));

        Instant expectedCutoff = FIXED_NOW.minus(properties.getLoginAttempt().getMaxAge());
        assertThat(cutoffCaptor.getValue().toInstant()).isEqualTo(expectedCutoff);
    }

    @Test
    @DisplayName("event_publication sweep only runs when enabled, mirroring every other target")
    void eventPublicationSweepRespectsEnabledFlag() {
        init();
        properties.getEventPublication().setEnabled(false);

        job.sweepEventPublication();

        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("chaincache_event_inbox sweep only touches PROCESSED rows, never QUARANTINED")
    void chaincacheEventInboxSweepsOnlyProcessedRows() {
        init();
        when(jdbc.update(anyString(), any(Timestamp.class), any(Integer.class))).thenReturn(0);

        job.sweepChaincacheEventInbox();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Timestamp.class), any(Integer.class));
        assertThat(sql.getValue()).contains("processing_state = 'PROCESSED'");
        assertThat(sql.getValue()).doesNotContain("QUARANTINED");
    }

    @Test
    @DisplayName("chaincache_event_inbox sweep respects its own enabled flag")
    void chaincacheEventInboxSweepRespectsEnabledFlag() {
        init();
        properties.getChaincacheEventInbox().setEnabled(false);

        job.sweepChaincacheEventInbox();

        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("wallet_bind_challenge sweep binds both the 'now' cutoff and the age cutoff")
    void walletBindChallengeSweepBindsTwoCutoffs() {
        init();
        when(jdbc.update(anyString(), any(Timestamp.class), any(Timestamp.class), any(Integer.class)))
                .thenReturn(0);

        job.sweepWalletBindChallenge();

        verify(jdbc).update(anyString(), any(Timestamp.class), any(Timestamp.class), any(Integer.class));
    }

    @Test
    @DisplayName("full sweep() touches every target exactly once when all are enabled")
    void fullSweepTouchesEveryEnabledTarget() {
        init();
        when(jdbc.update(anyString(), any(Timestamp.class), any(Integer.class))).thenReturn(0);
        when(jdbc.update(anyString(), any(Timestamp.class), any(Timestamp.class), any(Integer.class))).thenReturn(0);

        job.sweep();

        // login_attempt, webhook_delivery, event_publication, chaincache_event_inbox use the
        // 2-cutoff-arg overload; wallet_bind_challenge, onboarding_token, app_user_action_token
        // use the 3-arg overload.
        verify(jdbc, times(4)).update(anyString(), any(Timestamp.class), any(Integer.class));
        verify(jdbc, times(3)).update(anyString(), any(Timestamp.class), any(Timestamp.class), any(Integer.class));
    }
}
