package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.ClaimSigningService;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClaimIssuanceService — persists claims as submitted-not-confirmed, not as already-valid")
class ClaimIssuanceServiceTest {

    @Mock OnchainIdentityRepository identityRepository;
    @Mock OnchainClaimRepository claimRepository;
    @Mock Erc3643DeploymentService deploymentService;
    @Mock ClaimSigningService claimSigningService;
    @Mock ApplicationEventPublisher eventPublisher;

    private ClaimIssuanceService service;
    private final UUID legalEntityId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID identityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ClaimIssuanceService(identityRepository, claimRepository, deploymentService,
                claimSigningService, eventPublisher);
    }

    private OnchainIdentity identity() {
        OnchainIdentity identity = new OnchainIdentity();
        identity.setId(identityId);
        identity.setLegalEntityId(legalEntityId);
        identity.setChainConfigId(chainConfigId);
        identity.setIdentityAddress("0x-PENDING-not-relevant-here");
        return identity;
    }

    @Test
    @DisplayName("issueKycClaim persists the claim with txHash set but confirmed=false — not yet valid")
    void issueKycClaim_persistsUnconfirmed() {
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity()));
        when(deploymentService.issueKycClaim(identityId, ClaimIssuanceService.CLAIM_TOPIC_KYC, null))
                .thenReturn("0xissuetx");
        when(claimRepository.save(any(OnchainClaim.class))).thenAnswer(inv -> inv.getArgument(0));

        OnchainClaim saved = service.issueKycClaim(legalEntityId, chainConfigId, null, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(saved.getTxHash()).isEqualTo("0xissuetx");
        assertThat(saved.isConfirmed()).isFalse();
        verify(claimRepository).save(any(OnchainClaim.class));
    }

    @Test
    @DisplayName("issueKycClaim persists nothing when the on-chain submission throws")
    void issueKycClaim_persistsNothingWhenSubmissionFails() {
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity()));
        when(deploymentService.issueKycClaim(identityId, ClaimIssuanceService.CLAIM_TOPIC_KYC, null))
                .thenThrow(new IllegalStateException("identity not yet deployed"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.issueKycClaim(legalEntityId, chainConfigId, null, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("revokeClaim submits the revocation and tracks its tx but does not set revokedAt yet")
    void revokeClaim_tracksWithoutSettingRevokedAt() {
        UUID claimId = UUID.randomUUID();
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setOnchainIdentityId(identityId);
        when(claimRepository.findByIdAndOnchainIdentityId(claimId, identityId)).thenReturn(Optional.of(claim));
        when(deploymentService.revokeKycClaim(claimId)).thenReturn("0xrevoketx");

        service.revokeClaim(identityId, claimId, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(claim.getRevocationTxHash()).isEqualTo("0xrevoketx");
        assertThat(claim.getRevokedAt()).isNull();
        verify(claimRepository).save(claim);
    }

    @Test
    @DisplayName("revokeClaim is a no-op when the claim already has a revocation in flight")
    void revokeClaim_skipsWhenRevocationAlreadyPending() {
        UUID claimId = UUID.randomUUID();
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setOnchainIdentityId(identityId);
        claim.setRevocationTxHash("0xalready-pending");
        when(claimRepository.findByIdAndOnchainIdentityId(claimId, identityId)).thenReturn(Optional.of(claim));

        service.revokeClaim(identityId, claimId, UUID.randomUUID(), "REGISTRY_ADMIN");

        verify(deploymentService, never()).revokeKycClaim(any());
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("revokeClaim is a no-op when the claim is already revoked")
    void revokeClaim_skipsWhenAlreadyRevoked() {
        UUID claimId = UUID.randomUUID();
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setOnchainIdentityId(identityId);
        claim.setRevokedAt(Instant.now());
        when(claimRepository.findByIdAndOnchainIdentityId(claimId, identityId)).thenReturn(Optional.of(claim));

        service.revokeClaim(identityId, claimId, UUID.randomUUID(), "REGISTRY_ADMIN");

        verify(deploymentService, never()).revokeKycClaim(any());
    }

    @Test
    @DisplayName("getActiveClaims excludes claims that are not yet confirmed")
    void getActiveClaims_excludesUnconfirmed() {
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity()));

        OnchainClaim unconfirmed = new OnchainClaim();
        unconfirmed.setId(UUID.randomUUID());
        unconfirmed.setOnchainIdentityId(identityId);
        unconfirmed.setConfirmed(false);

        OnchainClaim confirmed = new OnchainClaim();
        confirmed.setId(UUID.randomUUID());
        confirmed.setOnchainIdentityId(identityId);
        confirmed.setConfirmed(true);

        when(claimRepository.findByOnchainIdentityId(identityId)).thenReturn(List.of(unconfirmed, confirmed));

        List<OnchainClaim> active = service.getActiveClaims(legalEntityId, chainConfigId);

        assertThat(active).containsExactly(confirmed);
    }

    @Test
    @DisplayName("getActiveClaims excludes a confirmed claim as soon as revocation is pending")
    void getActiveClaims_excludesPendingRevocationFailClosed() {
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity()));
        OnchainClaim claim = confirmedClaim();
        claim.setRevocationTxHash("0xpending-revocation");
        when(claimRepository.findByOnchainIdentityId(identityId)).thenReturn(List.of(claim));

        assertThat(service.getActiveClaims(legalEntityId, chainConfigId)).isEmpty();
    }

    @Test
    @DisplayName("getActiveClaims restores a claim after a confirmed failed revocation clears its intent")
    void getActiveClaims_includesAfterConfirmedFailureClearsRevocationIntent() {
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity()));
        OnchainClaim claim = confirmedClaim();
        claim.setRevocationTxHash(null); // state produced only by confirmed-failure reconciliation
        when(claimRepository.findByOnchainIdentityId(identityId)).thenReturn(List.of(claim));

        assertThat(service.getActiveClaims(legalEntityId, chainConfigId)).containsExactly(claim);
    }

    @Test
    @DisplayName("getActiveClaims keeps a reorg-reverted revocation excluded while its tx is pending again")
    void getActiveClaims_excludesReorgRevertedRevocationFailClosed() {
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity()));
        OnchainClaim claim = confirmedClaim();
        claim.setRevokedAt(null); // compensation clears only chain-derived state
        claim.setRevocationTxHash("0xreorged-revocation");
        when(claimRepository.findByOnchainIdentityId(identityId)).thenReturn(List.of(claim));

        assertThat(service.getActiveClaims(legalEntityId, chainConfigId)).isEmpty();
    }

    private OnchainClaim confirmedClaim() {
        OnchainClaim claim = new OnchainClaim();
        claim.setId(UUID.randomUUID());
        claim.setOnchainIdentityId(identityId);
        claim.setConfirmed(true);
        return claim;
    }
}
