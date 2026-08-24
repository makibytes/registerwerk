package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.RoleRestrictionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemainingOrgTransitionRevertCompensatorTest {

    private final UUID entityId = UUID.randomUUID();
    private final UUID chainId = UUID.randomUUID();

    private ChainEffectRecord effect(String type, String hash, Map<String, Object> before,
                                     Map<String, Object> after) {
        return new ChainEffectRecord(UUID.randomUUID(), chainId, 42L, hash, "0xtx", null,
                "orgidentity", type, "entity", entityId, null, CompensationCategory.INVERSE_FLIP,
                before, after, null, null, "COMPENSATING", 1, Instant.now());
    }

    private OrgRegistration statusRegistration(OrgRegistrationStatus status, String hash) {
        OrgRegistration registration = new OrgRegistration();
        registration.setStatus(status);
        registration.setStatusTx("0xtx");
        registration.setStatusChainConfigId(chainId);
        registration.setStatusBlockNumber(42L);
        registration.setStatusBlockHash(hash);
        return registration;
    }

    @Test
    void exactSuspensionReturnsToPendingButStaleIncarnationCannotUndo() {
        OrgRegistrationRepository repository = mock(OrgRegistrationRepository.class);
        OrgRegistration registration = statusRegistration(OrgRegistrationStatus.SUSPENDED, "0xnew");
        when(repository.findById(entityId)).thenReturn(Optional.of(registration));
        var compensator = new OrgSuspensionRevertCompensator(repository);

        assertThat(compensator.compensate(effect(compensator.effectType(), "0xold", null, null)))
                .isInstanceOf(CompensationOutcome.NotApplicable.class);
        verify(repository, never()).save(any());

        assertThat(compensator.compensate(effect(compensator.effectType(), "0xnew", null, null)))
                .isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(registration.getStatus()).isEqualTo(OrgRegistrationStatus.SUSPEND_PENDING);
        assertThat(registration.getStatusTx()).isEqualTo("0xtx");
        assertThat(registration.getStatusBlockHash()).isNull();
    }

    @Test
    void exactReinstatementReturnsToFailClosedPending() {
        OrgRegistrationRepository repository = mock(OrgRegistrationRepository.class);
        OrgRegistration registration = statusRegistration(OrgRegistrationStatus.ACTIVE, "0xblock");
        when(repository.findById(entityId)).thenReturn(Optional.of(registration));

        var outcome = new OrgReinstatementRevertCompensator(repository).compensate(
                effect(OrgReinstatementRevertCompensator.EFFECT_TYPE, "0xblock", null, null));

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(registration.getStatus()).isEqualTo(OrgRegistrationStatus.REINSTATE_PENDING);
    }

    @Test
    void exactMemberRemovalReturnsToFailClosedPending() {
        OrgMemberWalletRepository repository = mock(OrgMemberWalletRepository.class);
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setStatus(MemberWalletStatus.REMOVED);
        wallet.setRemovedTx("0xtx");
        wallet.setRemovedChainConfigId(chainId);
        wallet.setRemovedBlockNumber(42L);
        wallet.setRemovedBlockHash("0xblock");
        when(repository.findById(entityId)).thenReturn(Optional.of(wallet));

        var outcome = new MemberWalletRemovalRevertCompensator(repository).compensate(
                effect(MemberWalletRemovalRevertCompensator.EFFECT_TYPE, "0xblock", null, null));

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(wallet.getStatus()).isEqualTo(MemberWalletStatus.REMOVAL_PENDING);
        assertThat(wallet.getRemovedTx()).isEqualTo("0xtx");
    }

    @Test
    void roleRestrictionRestoresPreimageAndRetainsConservativeDesiredValue() {
        PermissionGrantRepository repository = mock(PermissionGrantRepository.class);
        PermissionGrant grant = new PermissionGrant();
        grant.setRoleRestricted(true);
        grant.setRoleRestrictionStatus(RoleRestrictionStatus.STABLE);
        grant.setRoleRestrictionTx("0xtx");
        grant.setRoleRestrictionChainConfigId(chainId);
        grant.setRoleRestrictionBlockNumber(42L);
        grant.setRoleRestrictionBlockHash("0xblock");
        when(repository.findById(entityId)).thenReturn(Optional.of(grant));

        var outcome = new RoleRestrictionRevertCompensator(repository).compensate(
                effect(RoleRestrictionRevertCompensator.EFFECT_TYPE, "0xblock",
                        Map.of("roleRestricted", false), Map.of("roleRestricted", true)));

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(grant.isConfirmedRoleRestricted()).isFalse();
        assertThat(grant.isRoleRestricted()).isTrue();
        assertThat(grant.getRequestedRoleRestricted()).isTrue();
        assertThat(grant.getRoleRestrictionStatus()).isEqualTo(RoleRestrictionStatus.CHANGE_PENDING);
    }

    @Test
    void repeatedRoleRestrictionLifoRestoresOriginalValueAndPreservesLatestPendingIntent() {
        PermissionGrantRepository repository = mock(PermissionGrantRepository.class);
        PermissionGrant grant = new PermissionGrant();
        grant.setRoleRestricted(false);
        grant.setRoleRestrictionStatus(RoleRestrictionStatus.STABLE);
        grant.setRoleRestrictionTx("0xsecond");
        grant.setRoleRestrictionChainConfigId(chainId);
        grant.setRoleRestrictionBlockNumber(43L);
        grant.setRoleRestrictionBlockHash("0xsecondblock");
        Instant latestRequestedAt = Instant.parse("2026-08-23T10:16:30Z");
        grant.setRoleRestrictionRequestedAt(latestRequestedAt);
        when(repository.findById(entityId)).thenReturn(Optional.of(grant));

        ChainEffectRecord secondEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainId, 43L, "0xsecondblock", "0xsecond", null,
                "orgidentity", RoleRestrictionRevertCompensator.EFFECT_TYPE,
                "PermissionGrant", entityId, null, CompensationCategory.INVERSE_FLIP,
                Map.of("roleRestricted", true), Map.of("roleRestricted", false),
                null, null, "COMPENSATING", 1, Instant.now());
        ChainEffectRecord firstEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainId, 42L, "0xfirstblock", "0xfirst", null,
                "orgidentity", RoleRestrictionRevertCompensator.EFFECT_TYPE,
                "PermissionGrant", entityId, null, CompensationCategory.INVERSE_FLIP,
                Map.of("roleRestricted", false), Map.of("roleRestricted", true),
                null, null, "COMPENSATING", 1, Instant.now());
        var compensator = new RoleRestrictionRevertCompensator(repository);

        CompensationOutcome secondOutcome = compensator.compensate(secondEffect);
        CompensationOutcome firstOutcome = compensator.compensate(firstEffect);

        assertThat(secondOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(firstOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(grant.isConfirmedRoleRestricted()).isFalse();
        assertThat(grant.isRoleRestricted()).isFalse();
        assertThat(grant.getRequestedRoleRestricted()).isFalse();
        assertThat(grant.getRoleRestrictionStatus()).isEqualTo(RoleRestrictionStatus.CHANGE_PENDING);
        assertThat(grant.getRoleRestrictionTx()).isEqualTo("0xsecond");
        assertThat(grant.getRoleRestrictionRequestedAt()).isEqualTo(latestRequestedAt);
        assertThat(grant.getRoleRestrictionChainConfigId()).isNull();
        assertThat(grant.getRoleRestrictionBlockNumber()).isNull();
        assertThat(grant.getRoleRestrictionBlockHash()).isNull();
    }

    @Test
    void malformedPendingRoleRestrictionCannotSupersedeOlderEffect() {
        PermissionGrantRepository repository = mock(PermissionGrantRepository.class);
        PermissionGrant grant = new PermissionGrant();
        grant.setRoleRestricted(true);
        grant.setRoleRestrictionStatus(RoleRestrictionStatus.CHANGE_PENDING);
        grant.setRequestedRoleRestricted(false);
        grant.setRoleRestrictionTx("0xsecond");
        // roleRestrictionRequestedAt is deliberately absent.
        when(repository.findById(entityId)).thenReturn(Optional.of(grant));

        CompensationOutcome outcome = new RoleRestrictionRevertCompensator(repository).compensate(
                effect(RoleRestrictionRevertCompensator.EFFECT_TYPE, "0xfirstblock",
                        Map.of("roleRestricted", false), Map.of("roleRestricted", true)));

        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
        verify(repository, never()).save(any());
    }
}
