package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.stepup.api.DualControlTarget;
import de.makibytes.registerwerk.stepup.events.ApprovalRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Approval queue service (T8-02)")
class ApprovalRequestServiceTest {

    private static final String ACTION = "TOTP_RESET";
    private static final String PATH = "/api/v1/admin/users/11111111-1111-1111-1111-111111111111/totp-reset";

    @Mock ApprovalRequestRepository repository;
    @Mock ApprovalActionCatalog catalog;
    @Mock StepUpTokenIssuer issuer;
    @Mock StepUpEnforcer enforcer;
    @Mock AppUserRepository users;
    @Mock ApplicationEventPublisher events;
    @Mock PlatformTransactionManager txManager;

    private final ApprovalQueueProperties properties = new ApprovalQueueProperties();
    private final DualControlProperties dualControl = new DualControlProperties();
    private ApprovalRequestService service;

    private final UUID requesterId = UUID.randomUUID();
    private final UUID approverId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final ApprovalRequestService.Caller requester = new ApprovalRequestService.Caller(requesterId, "REGISTRY_ADMIN", null);
    private final ApprovalRequestService.Caller approver = new ApprovalRequestService.Caller(approverId, "COMPLIANCE_OFFICER", null);

    @BeforeEach
    void setUp() {
        service = new ApprovalRequestService(repository, catalog, properties, dualControl, issuer, enforcer, users,
                events, txManager);
        org.mockito.Mockito.lenient().when(catalog.accepts(eq(ACTION), anyString(), eq(PATH))).thenReturn(true);
        org.mockito.Mockito.lenient().when(enforcer.mode()).thenReturn(StepUpMode.LOCAL_TOTP);
        org.mockito.Mockito.lenient().when(users.findById(approverId)).thenReturn(Optional.of(user(approverId, true, AppUserRole.COMPLIANCE_OFFICER)));
        org.mockito.Mockito.lenient().when(users.findById(requesterId)).thenReturn(Optional.of(user(requesterId, true, AppUserRole.REGISTRY_ADMIN)));
    }

    private static AppUser user(UUID id, boolean enabled, AppUserRole role) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setEnabled(enabled);
        u.setRoles(Set.of(role));
        return u;
    }

    private ApprovalRequestRecord record(String status, UUID requester, UUID approver, Instant expiresAt) {
        return new ApprovalRequestRecord(requestId, requester, "r@x", ACTION, "POST", PATH, null, "", "digest", status,
                approver, null, Instant.now().minusSeconds(60), expiresAt, null, null, null);
    }

    private ApprovalRequestRecord open(String status, UUID requester, UUID approver) {
        return record(status, requester, approver, Instant.now().plusSeconds(600));
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create binds the exact request: the stored digest is DualControlTarget's digest of method, path, query and canonical body")
    void createStoresTheSharedDigest() {
        when(repository.find(any())).thenReturn(Optional.of(open("PENDING", requesterId, null)));
        var body = JsonMapper.builder().build().readTree("{\"b\":1.50,\"a\":\"x\"}");

        service.create(requester, new ApprovalRequestService.Draft(ACTION, "post", PATH, "z=1&a=2", body));

        ArgumentCaptor<String> canonical = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(any(), eq(requesterId), eq(ACTION), eq("POST"), eq(PATH), eq("z=1&a=2"),
                canonical.capture(), digest.capture(), any());
        assertThat(canonical.getValue()).isEqualTo("{\"a\":\"x\",\"b\":1.5}");
        assertThat(digest.getValue()).isEqualTo(DualControlTarget.digest("POST", PATH, "z=1&a=2", canonical.getValue()));
        verify(events).publishEvent(any(ApprovalRequestEvent.class));
    }

    @Test
    @DisplayName("an action that is not a four-eyes action of that route is refused and nothing is stored")
    void unknownActionIsRefused() {
        assertThatThrownBy(() -> service.create(requester,
                new ApprovalRequestService.Draft("MADE_UP", "POST", PATH, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown action");
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an oversized body, a body for a secret-bearing reason and a path outside /api/v1 are refused")
    void invalidDraftsAreRefused() {
        properties.setMaxBodyBytes(16);
        var big = JsonMapper.builder().build().readTree("{\"note\":\"" + "x".repeat(64) + "\"}");
        assertThatThrownBy(() -> service.create(requester, new ApprovalRequestService.Draft(ACTION, "POST", PATH, null, big)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");

        dualControl.setBodyOptOutReasons(java.util.List.of(ACTION));
        var anyBody = JsonMapper.builder().build().readTree("{\"privateKey\":\"0x01\"}");
        assertThatThrownBy(() -> service.create(requester, new ApprovalRequestService.Draft(ACTION, "POST", PATH, null, anyBody)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not part of an approval");

        assertThatThrownBy(() -> service.create(requester,
                new ApprovalRequestService.Draft(ACTION, "POST", "/actuator/env", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a requester with too many open requests gets no further one")
    void openRequestsAreCapped() {
        properties.setMaxOpenPerRequester(2);
        when(repository.countOpen(requesterId)).thenReturn(2);
        assertThatThrownBy(() -> service.create(requester, new ApprovalRequestService.Draft(ACTION, "POST", PATH, null, null)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ── approve / reject ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the requester cannot approve or reject their own request, and no TOTP code is spent trying")
    void requesterCannotDecideOwnRequest() {
        when(repository.find(requestId)).thenReturn(Optional.of(open("PENDING", requesterId, null)));

        assertThatThrownBy(() -> service.approve(requester, requestId, "123456", null))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("person who made it");
        assertThatThrownBy(() -> service.reject(requester, requestId, null)).isInstanceOf(AccessDeniedException.class);

        verify(repository, never()).decide(any(), any(), anyString(), any());
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("only an enabled REGISTRY_ADMIN / COMPLIANCE_OFFICER decides: a disabled or other-role user is refused")
    void ineligibleApproverIsRefused() {
        UUID other = UUID.randomUUID();
        when(users.findById(other)).thenReturn(Optional.of(user(other, true, AppUserRole.INVESTOR)));
        var caller = new ApprovalRequestService.Caller(other, "INVESTOR", null);
        when(users.findById(approverId)).thenReturn(Optional.of(user(approverId, false, AppUserRole.REGISTRY_ADMIN)));

        assertThatThrownBy(() -> service.approve(caller, requestId, "123456", null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.approve(approver, requestId, "123456", null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.pending(caller, 0, 20)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(issuer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "CANCELLED", "EXPIRED", "CLAIMED"})
    @DisplayName("a request that is not PENDING cannot be approved")
    void nonPendingCannotBeApproved(String status) {
        when(repository.find(requestId)).thenReturn(Optional.of(open(status, requesterId, approverId)));
        assertThatThrownBy(() -> service.approve(approver, requestId, "123456", null))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(repository, never()).decide(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("a PENDING request past its expiry cannot be approved or rejected")
    void expiredCannotBeDecided() {
        when(repository.find(requestId)).thenReturn(
                Optional.of(record("PENDING", requesterId, null, Instant.now().minusSeconds(1))));
        assertThatThrownBy(() -> service.approve(approver, requestId, "123456", null))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> service.reject(approver, requestId, null)).isInstanceOf(InvalidStateTransitionException.class);
        verify(repository, never()).decide(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("approve proves the approver's TOTP code before the atomic update; the loser of a race gets a conflict")
    void approveChecksTotpThenDecides() {
        when(repository.find(requestId)).thenReturn(Optional.of(open("PENDING", requesterId, null)));
        when(repository.decide(eq(requestId), eq(approverId), eq("APPROVED"), any())).thenReturn(false);

        assertThatThrownBy(() -> service.approve(approver, requestId, "123456", "ok"))
                .isInstanceOf(InvalidStateTransitionException.class);

        InOrder order = inOrder(issuer, repository);
        order.verify(issuer).verifySecondFactor(approverId, "123456");
        order.verify(repository).decide(requestId, approverId, "APPROVED", "ok");
        verify(events, never()).publishEvent(any(ApprovalRequestEvent.class));
    }

    @Test
    @DisplayName("a wrong TOTP code leaves the request untouched")
    void wrongCodeDoesNotDecide() {
        when(repository.find(requestId)).thenReturn(Optional.of(open("PENDING", requesterId, null)));
        org.mockito.Mockito.doThrow(new AccessDeniedException("Invalid TOTP code")).when(issuer).verifySecondFactor(any(), any());
        assertThatThrownBy(() -> service.approve(approver, requestId, "000000", null)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).decide(any(), any(), anyString(), any());
    }

    // ── cancel / claim ───────────────────────────────────────────────────────

    @Test
    @DisplayName("only the requester can claim, and nothing is minted for anybody else - the approver included")
    void claimIsRequesterOnly() {
        when(repository.find(requestId)).thenReturn(Optional.of(open("APPROVED", requesterId, approverId)));
        assertThatThrownBy(() -> service.claim(approver, requestId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.cancel(approver, requestId)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).claim(any(), any(), any());
        verify(issuer, never()).mintApprovalToken(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "CANCELLED", "EXPIRED", "CLAIMED"})
    @DisplayName("only an APPROVED request can be claimed")
    void onlyApprovedCanBeClaimed(String status) {
        when(repository.find(requestId)).thenReturn(Optional.of(open(status, requesterId, approverId)));
        assertThatThrownBy(() -> service.claim(requester, requestId)).isInstanceOf(InvalidStateTransitionException.class);
        verify(repository, never()).claim(any(), any(), any());
    }

    @Test
    @DisplayName("an approved request past its expiry cannot be claimed")
    void expiredApprovalCannotBeClaimed() {
        when(repository.find(requestId)).thenReturn(
                Optional.of(record("APPROVED", requesterId, approverId, Instant.now().minusSeconds(1))));
        assertThatThrownBy(() -> service.claim(requester, requestId)).isInstanceOf(InvalidStateTransitionException.class);
        verify(issuer, never()).mintApprovalToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("claim re-checks the approver: an approver disabled since approving yields no token")
    void claimRechecksApproverEligibility() {
        when(repository.find(requestId)).thenReturn(Optional.of(open("APPROVED", requesterId, approverId)));
        when(users.findById(approverId)).thenReturn(Optional.of(user(approverId, false, AppUserRole.REGISTRY_ADMIN)));
        assertThatThrownBy(() -> service.claim(requester, requestId)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).claim(any(), any(), any());

        when(users.findById(approverId)).thenReturn(Optional.of(user(approverId, true, AppUserRole.INVESTOR)));
        assertThatThrownBy(() -> service.claim(requester, requestId)).isInstanceOf(AccessDeniedException.class);
        verify(issuer, never()).mintApprovalToken(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("claim mints the token for exactly this request's action and digest, with the jti the row records")
    void claimMintsTheBoundToken() {
        ApprovalRequestRecord approved = open("APPROVED", requesterId, approverId);
        when(repository.find(requestId)).thenReturn(Optional.of(approved));
        when(repository.claim(eq(requestId), eq(requesterId), anyString())).thenReturn(true);
        when(issuer.mintApprovalToken(any(), any(), any(), any(), any(), any()))
                .thenReturn(new StepUpTokenIssuer.MintedApproval("tok", Instant.now().plus(Duration.ofMinutes(5))));

        var claim = service.claim(requester, requestId);

        ArgumentCaptor<String> jtiInRow = ArgumentCaptor.forClass(String.class);
        verify(repository).claim(eq(requestId), eq(requesterId), jtiInRow.capture());
        verify(issuer).mintApprovalToken(any(AppUser.class), eq(ACTION), eq("digest"), eq(requesterId), eq(requestId),
                eq(jtiInRow.getValue()));
        assertThat(claim.approvalToken()).isEqualTo("tok");
        assertThat(claim.target()).isEqualTo("POST " + PATH);
    }

    @Test
    @DisplayName("losing the claim race yields no token")
    void lostClaimRaceMintsNothing() {
        when(repository.find(requestId)).thenReturn(Optional.of(open("APPROVED", requesterId, approverId)));
        when(repository.claim(any(), any(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.claim(requester, requestId)).isInstanceOf(InvalidStateTransitionException.class);
        verify(issuer, never()).mintApprovalToken(any(), any(), any(), any(), any(), any());
    }

    // ── read ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a request is visible to its requester and to eligible approvers only")
    void visibility() {
        UUID stranger = UUID.randomUUID();
        when(users.findById(stranger)).thenReturn(Optional.of(user(stranger, true, AppUserRole.INVESTOR)));
        when(repository.find(requestId)).thenReturn(Optional.of(open("PENDING", requesterId, null)));

        assertThat(service.get(requester, requestId)).isNotNull();
        assertThat(service.get(approver, requestId)).isNotNull();
        assertThatThrownBy(() -> service.get(new ApprovalRequestService.Caller(stranger, "INVESTOR", null), requestId))
                .isInstanceOf(de.makibytes.registerwerk.shared.EntityNotFoundException.class);
    }
}
