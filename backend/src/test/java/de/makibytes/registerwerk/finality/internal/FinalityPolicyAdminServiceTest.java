package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityPolicyProfile;
import de.makibytes.registerwerk.finality.events.FinalityPolicyChangedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinalityPolicyAdminService — assignment/override CRUD, auditing")
class FinalityPolicyAdminServiceTest {

    @Mock private FinalityPolicyAssignmentRepository assignmentRepository;
    @Mock private FinalityPolicyOverrideRepository overrideRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private FinalityPolicyAdminService service;
    private final UUID actorId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FinalityPolicyAdminService(assignmentRepository, overrideRepository, eventPublisher);
        lenient().when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(overrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("setGlobalProfile creates a new GLOBAL assignment and publishes an audit event")
    void setGlobalProfile_creates_publishesEvent() {
        when(assignmentRepository.findByScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL))
                .thenReturn(Optional.empty());

        var view = service.setGlobalProfile(FinalityPolicyProfile.FAST, actorId, "REGISTRY_ADMIN");

        assertThat(view.scopeType()).isEqualTo("GLOBAL");
        assertThat(view.profile()).isEqualTo(FinalityPolicyProfile.FAST);
        ArgumentCaptor<FinalityPolicyChangedEvent> captor = ArgumentCaptor.forClass(FinalityPolicyChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo("GLOBAL_PROFILE_SET");
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("setGlobalProfile updates the existing GLOBAL row instead of creating a second one")
    void setGlobalProfile_updatesExisting() {
        FinalityPolicyAssignment existing = new FinalityPolicyAssignment();
        existing.setScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL);
        existing.setProfile(FinalityPolicyProfile.BALANCED);
        when(assignmentRepository.findByScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL))
                .thenReturn(Optional.of(existing));

        service.setGlobalProfile(FinalityPolicyProfile.CONSERVATIVE, actorId, "REGISTRY_ADMIN");

        assertThat(existing.getProfile()).isEqualTo(FinalityPolicyProfile.CONSERVATIVE);
        verify(assignmentRepository).save(existing);
    }

    @Test
    @DisplayName("setTokenStandardProfile stamps the scope and token standard correctly")
    void setTokenStandardProfile_stampsScope() {
        when(assignmentRepository.findByScopeTypeAndTokenStandard(
                FinalityPolicyAssignment.ScopeType.TOKEN_STANDARD, TokenStandard.ERC3643))
                .thenReturn(Optional.empty());

        var view = service.setTokenStandardProfile(TokenStandard.ERC3643, FinalityPolicyProfile.FAST, actorId, "REGISTRY_ADMIN");

        assertThat(view.scopeType()).isEqualTo("TOKEN_STANDARD");
        assertThat(view.tokenStandard()).isEqualTo(TokenStandard.ERC3643);
    }

    @Test
    @DisplayName("setAssetProfile stamps the scope and asset id correctly")
    void setAssetProfile_stampsScope() {
        when(assignmentRepository.findByScopeTypeAndAssetId(FinalityPolicyAssignment.ScopeType.ASSET, assetId))
                .thenReturn(Optional.empty());

        var view = service.setAssetProfile(assetId, FinalityPolicyProfile.CONSERVATIVE, actorId, "REGISTRY_ADMIN");

        assertThat(view.scopeType()).isEqualTo("ASSET");
        assertThat(view.assetId()).isEqualTo(assetId);
    }

    @Test
    @DisplayName("deleteAssignment removes the row and publishes an audit event")
    void deleteAssignment_removesAndPublishes() {
        UUID assignmentId = UUID.randomUUID();
        FinalityPolicyAssignment assignment = new FinalityPolicyAssignment();
        assignment.setScopeType(FinalityPolicyAssignment.ScopeType.GLOBAL);
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));

        service.deleteAssignment(assignmentId, actorId, "REGISTRY_ADMIN");

        verify(assignmentRepository).delete(assignment);
        verify(eventPublisher).publishEvent(any(FinalityPolicyChangedEvent.class));
    }

    @Test
    @DisplayName("deleteAssignment on an unknown id throws EntityNotFoundException")
    void deleteAssignment_unknownId_throws() {
        UUID assignmentId = UUID.randomUUID();
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAssignment(assignmentId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("createOverride requires and records a reason, publishes an audit event")
    void createOverride_recordsReasonAndPublishes() {
        when(overrideRepository.findByAssetIdAndOperation(assetId, "AUTHORITATIVE_BALANCE"))
                .thenReturn(Optional.empty());

        var view = service.createOverride(assetId, "AUTHORITATIVE_BALANCE", FinalityLevel.SAFE,
                "Fast desk override for this bond", actorId, "REGISTRY_ADMIN");

        assertThat(view.reason()).isEqualTo("Fast desk override for this bond");
        assertThat(view.requiredLevel()).isEqualTo(FinalityLevel.SAFE);
        ArgumentCaptor<FinalityPolicyChangedEvent> captor = ArgumentCaptor.forClass(FinalityPolicyChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo("OVERRIDE_SET");
    }

    @Test
    @DisplayName("deleteOverride removes the row and publishes an audit event")
    void deleteOverride_removesAndPublishes() {
        UUID overrideId = UUID.randomUUID();
        FinalityPolicyOverride override = new FinalityPolicyOverride();
        override.setAssetId(assetId);
        override.setOperation("AUTHORITATIVE_BALANCE");
        when(overrideRepository.findById(overrideId)).thenReturn(Optional.of(override));

        service.deleteOverride(overrideId, actorId, "REGISTRY_ADMIN");

        verify(overrideRepository).delete(override);
        verify(eventPublisher).publishEvent(any(FinalityPolicyChangedEvent.class));
    }
}
