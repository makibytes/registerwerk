package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies the fail-closed contract of the org-identity gate: only a confirmed
 * (ACTIVE) wallet binding to a confirmed, non-suspended org passes — pending
 * bindings, suspended orgs and unknown wallets never do.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionGateImpl fail-closed unit tests")
class PermissionGateImplTest {

    @Mock
    private OrgRegistrationRepository registrationRepository;

    @Mock
    private OrgMemberWalletRepository walletRepository;

    @InjectMocks
    private PermissionGateImpl gate;

    private final UUID entityId = UUID.randomUUID();
    private final UUID chainId = UUID.randomUUID();
    private final UUID orgRegistrationId = UUID.randomUUID();
    private final String wallet = "0x1111111111111111111111111111111111111111";

    private OrgMemberWallet wallet(MemberWalletStatus status) {
        OrgMemberWallet w = new OrgMemberWallet();
        w.setOrgRegistrationId(orgRegistrationId);
        w.setChainConfigId(chainId);
        w.setWalletAddress(wallet);
        w.setStatus(status);
        return w;
    }

    private OrgRegistration org(OrgRegistrationStatus status) {
        OrgRegistration org = new OrgRegistration();
        org.setId(orgRegistrationId);
        org.setLegalEntityId(entityId);
        org.setChainConfigId(chainId);
        org.setStatus(status);
        return org;
    }

    @Test
    @DisplayName("unknown wallet — never passes")
    void unknownWallet() {
        when(walletRepository.findLiveBinding(chainId, wallet)).thenReturn(Optional.empty());
        assertThat(gate.isWalletBoundToEntity(wallet, entityId, chainId)).isFalse();
        assertThat(gate.entityForWallet(wallet, chainId)).isEmpty();
    }

    @Test
    @DisplayName("pending binding — fails closed")
    void pendingBinding() {
        when(walletRepository.findLiveBinding(chainId, wallet))
                .thenReturn(Optional.of(wallet(MemberWalletStatus.PENDING)));
        assertThat(gate.isWalletBoundToEntity(wallet, entityId, chainId)).isFalse();
    }

    @Test
    @DisplayName("active binding to suspended org — fails closed")
    void suspendedOrg() {
        when(walletRepository.findLiveBinding(chainId, wallet))
                .thenReturn(Optional.of(wallet(MemberWalletStatus.ACTIVE)));
        when(registrationRepository.findById(orgRegistrationId))
                .thenReturn(Optional.of(org(OrgRegistrationStatus.SUSPENDED)));
        assertThat(gate.isWalletBoundToEntity(wallet, entityId, chainId)).isFalse();
    }

    @Test
    @DisplayName("active binding to active org — passes for the right entity only")
    void activeBinding() {
        when(walletRepository.findLiveBinding(chainId, wallet))
                .thenReturn(Optional.of(wallet(MemberWalletStatus.ACTIVE)));
        when(registrationRepository.findById(orgRegistrationId))
                .thenReturn(Optional.of(org(OrgRegistrationStatus.ACTIVE)));

        assertThat(gate.isWalletBoundToEntity(wallet, entityId, chainId)).isTrue();
        assertThat(gate.isWalletBoundToEntity(wallet, UUID.randomUUID(), chainId)).isFalse();
        assertThat(gate.entityForWallet(wallet, chainId)).contains(entityId);
    }

    @Test
    @DisplayName("org status checks are entity-scoped and fail closed")
    void orgActive() {
        when(registrationRepository.findByLegalEntityIdAndChainConfigId(entityId, chainId))
                .thenReturn(Optional.of(org(OrgRegistrationStatus.PENDING)));
        assertThat(gate.isOrgActive(entityId, chainId)).isFalse();

        when(registrationRepository.findByLegalEntityIdAndChainConfigId(entityId, chainId))
                .thenReturn(Optional.of(org(OrgRegistrationStatus.ACTIVE)));
        assertThat(gate.isOrgActive(entityId, chainId)).isTrue();

        when(registrationRepository.findByLegalEntityIdAndChainConfigId(entityId, chainId))
                .thenReturn(Optional.empty());
        assertThat(gate.isOrgActive(entityId, chainId)).isFalse();
    }
}
