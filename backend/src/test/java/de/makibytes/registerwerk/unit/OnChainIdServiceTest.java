package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.erc3643.internal.Erc3643DeploymentService;
import de.makibytes.registerwerk.erc3643.internal.OnChainIdService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnChainIdService.isVerified — suite-scoped required-topic checks")
class OnChainIdServiceTest {

    @Mock private OnchainIdentityRepository identityRepository;
    @Mock private OnchainClaimRepository claimRepository;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private Erc3643DeploymentService deploymentService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EvmContractService evmContractService;
    @Mock private DurableEvmTransactionGateway evmTransactions;
    @Mock private BlockchainClientRegistry blockchainClientRegistry;
    @Mock private ContractAddressConfig contractAddressConfig;
    @Mock private BlockchainTransactionService blockchainTransactionService;

    @InjectMocks
    private OnChainIdService service;

    @Test
    @DisplayName("missing IdFactory fails closed and does not persist an unresolvable identity")
    void getOrCreate_missingFactoryDoesNotPersistPlaceholder() {
        UUID legalEntityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        ChainConfig chain = new ChainConfig();
        chain.setId(chainConfigId);
        chain.setIdentifier("ETHEREUM_SEPOLIA");
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.empty());
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain));
        when(contractAddressConfig.requireIdFactory("ETHEREUM_SEPOLIA"))
                .thenThrow(new IllegalStateException("missing IdFactory"));

        assertThatThrownBy(() -> service.getOrCreate(
                legalEntityId, chainConfigId, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("deployIdentityProxy submission failed")
                .hasRootCauseMessage("missing IdFactory");

        verify(identityRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private OnchainClaim claim(long topic, Instant expiresAt, Instant revokedAt) {
        OnchainClaim c = new OnchainClaim();
        c.setTopic(topic);
        c.setExpiresAt(expiresAt);
        c.setRevokedAt(revokedAt);
        return c;
    }

    @Test
    @DisplayName("false when a required AML claim is missing, even though KYC is present")
    void isVerified_falseWhenOneOfMultipleRequiredTopicsIsMissing() {
        UUID legalEntityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        OnchainIdentity identity = new OnchainIdentity();
        identity.setId(UUID.randomUUID());
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity));
        when(claimRepository.findByOnchainIdentityId(identity.getId()))
                .thenReturn(List.of(claim(1L, null, null))); // KYC only

        boolean verified = service.isVerified(legalEntityId, chainConfigId, List.of(1L, 2L));

        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("false when the only matching claim for a required topic was revoked")
    void isVerified_falseWhenRequiredClaimIsRevoked() {
        UUID legalEntityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        OnchainIdentity identity = new OnchainIdentity();
        identity.setId(UUID.randomUUID());
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity));
        when(claimRepository.findByOnchainIdentityId(identity.getId()))
                .thenReturn(List.of(claim(2L, null, Instant.now()))); // AML revoked

        boolean verified = service.isVerified(legalEntityId, chainConfigId, List.of(2L));

        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("true when every required topic has a valid, non-revoked, non-expired claim")
    void isVerified_trueWhenAllRequiredTopicsSatisfied() {
        UUID legalEntityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        OnchainIdentity identity = new OnchainIdentity();
        identity.setId(UUID.randomUUID());
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.of(identity));
        when(claimRepository.findByOnchainIdentityId(identity.getId()))
                .thenReturn(List.of(claim(1L, null, null), claim(2L, null, null)));

        boolean verified = service.isVerified(legalEntityId, chainConfigId, List.of(1L, 2L));

        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("false when no ONCHAINID identity exists at all")
    void isVerified_falseWhenNoIdentity() {
        UUID legalEntityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        when(identityRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId))
                .thenReturn(Optional.empty());

        boolean verified = service.isVerified(legalEntityId, chainConfigId, List.of(1L));

        assertThat(verified).isFalse();
    }
}
