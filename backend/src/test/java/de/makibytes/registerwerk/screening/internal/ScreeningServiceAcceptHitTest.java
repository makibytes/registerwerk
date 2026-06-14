package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies the four-eyes controls on screening-hit acceptance:
 * mandatory reason, mandatory second approver for high-score hits,
 * and rejection of self-approval.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScreeningService.acceptHit dual-control unit tests")
class ScreeningServiceAcceptHitTest {

    @Mock
    private ScreeningRunRepository runRepository;

    @Mock
    private ScreeningHitRepository hitRepository;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private LegalEntityRepository legalEntityRepository;

    private ScreeningService service;

    private final UUID hitId = UUID.randomUUID();
    private final UUID officer = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ScreeningService(
                List.<SanctionsScreeningPort>of(), runRepository, hitRepository, events, legalEntityRepository);
    }

    private ScreeningHit hitWithScore(String score) {
        ScreeningHit hit = new ScreeningHit();
        hit.setMatchScore(new BigDecimal(score));
        when(hitRepository.findById(hitId)).thenReturn(Optional.of(hit));
        return hit;
    }

    @Test
    @DisplayName("blank reason is rejected (GwG §8 documentation duty)")
    void blankReason_rejected() {
        hitWithScore("0.50");
        assertThatThrownBy(() -> service.acceptHit(hitId, officer, null, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is mandatory");
    }

    @Test
    @DisplayName("high-score hit without second approver is rejected (four-eyes)")
    void highScoreWithoutApprover_rejected() {
        hitWithScore("0.92");
        assertThatThrownBy(() -> service.acceptHit(hitId, officer, null, "false positive"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dual control required");
    }

    @Test
    @DisplayName("self-approval is rejected — approver must differ from officer")
    void selfApproval_rejected() {
        hitWithScore("0.92");
        assertThatThrownBy(() -> service.acceptHit(hitId, officer, officer, "false positive"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different user");
    }

    @Test
    @DisplayName("low-score hit can be accepted by a single officer with reason")
    void lowScoreSingleOfficer_accepted() {
        ScreeningHit hit = hitWithScore("0.50");
        when(hitRepository.save(any(ScreeningHit.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreeningHit accepted = service.acceptHit(hitId, officer, null, "name collision, different DOB");

        assertThat(accepted.getAccepted()).isTrue();
        assertThat(accepted.getAcceptedBy()).isEqualTo(officer);
        assertThat(hit.getDualControlApproverId()).isNull();
    }

    @Test
    @DisplayName("high-score hit with distinct second approver is accepted and recorded")
    void highScoreWithApprover_accepted() {
        ScreeningHit hit = hitWithScore("0.95");
        when(hitRepository.save(any(ScreeningHit.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreeningHit accepted = service.acceptHit(hitId, officer, approver, "verified different entity via register");

        assertThat(accepted.getAccepted()).isTrue();
        assertThat(accepted.getDualControlApproverId()).isEqualTo(approver);
        assertThat(accepted.getDualControlApprovedAt()).isNotNull();
    }
}
