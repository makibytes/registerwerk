package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappPaymentMethodRepository;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermissionRepository;
import de.makibytes.registerwerk.marketplace.api.DappReviewEventRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.web3j.crypto.Hash;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EwpgPaymaster (Wave 1 contracts, H12) gates {@code registerPolicy} on {@code paymaster.register-policy}
 * and its safety valve on {@code paymaster.configure} bound to the operator org. The demo ecosystem has
 * no paymaster deployed, but the permission definitions must exist (with the exact contract hashes) so
 * an operator who deploys one can grant them, and the demo must not hand the operator-only permission
 * to a customer org.
 */
@DisplayName("EcosystemDemoDataSeeder: paymaster permissions")
class EcosystemDemoDataSeederPaymasterTest {

    private final PermissionDefinitionRepository definitions = mock(PermissionDefinitionRepository.class);
    private final PermissionGrantRepository grants = mock(PermissionGrantRepository.class);
    private final EcosystemDemoDataSeeder seeder = new EcosystemDemoDataSeeder(
            mock(LegalEntityRepository.class), mock(AppUserRepository.class), mock(ChainConfigRepository.class),
            mock(OrgRegistrationRepository.class), mock(OrgMemberWalletRepository.class),
            mock(EcosystemTrustedIssuerRepository.class), definitions, grants, mock(DappListingRepository.class),
            mock(DappVersionRepository.class), mock(DappRequiredPermissionRepository.class),
            mock(DappPaymentMethodRepository.class), mock(DappReviewEventRepository.class),
            mock(PaymentRailRepository.class), mock(PaymentRailChainAddressRepository.class),
            mock(PasswordEncoder.class), mock(AssetRepository.class), mock(AssetTokenAdminGrantRepository.class));

    private static String keccak(String code) {
        return org.web3j.utils.Numeric.toHexString(Hash.sha3(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("defines paymaster.register-policy and paymaster.configure with the contract's keccak256 ids")
    void definesBothPaymasterPermissions() {
        when(definitions.save(any(PermissionDefinition.class))).thenAnswer(inv -> {
            PermissionDefinition d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        OrgRegistration meridianOrg = new OrgRegistration();
        meridianOrg.setId(UUID.randomUUID());

        seeder.seedPaymasterPermissions(meridianOrg);

        ArgumentCaptor<PermissionDefinition> saved = ArgumentCaptor.forClass(PermissionDefinition.class);
        verify(definitions, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PermissionDefinition::getCode)
                .containsExactlyInAnyOrder("paymaster.register-policy", "paymaster.configure");
        assertThat(saved.getAllValues()).allSatisfy(d ->
                assertThat(d.getPermissionHash()).isEqualTo(keccak(d.getCode())));
    }

    @Test
    @DisplayName("grants register-policy to the issuer sponsor org only; configure stays operator-org only (none in demo)")
    void grantsRegisterPolicyToTheSponsorAndNeverConfigureToACustomerOrg() {
        when(definitions.save(any(PermissionDefinition.class))).thenAnswer(inv -> {
            PermissionDefinition d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        OrgRegistration meridianOrg = new OrgRegistration();
        meridianOrg.setId(UUID.randomUUID());

        seeder.seedPaymasterPermissions(meridianOrg);

        ArgumentCaptor<PermissionGrant> granted = ArgumentCaptor.forClass(PermissionGrant.class);
        verify(grants).save(granted.capture());
        verify(grants, never()).saveAll(any());
        List<PermissionGrant> all = granted.getAllValues();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getOrgRegistrationId()).isEqualTo(meridianOrg.getId());
        ArgumentCaptor<PermissionDefinition> defs = ArgumentCaptor.forClass(PermissionDefinition.class);
        verify(definitions, org.mockito.Mockito.times(2)).save(defs.capture());
        PermissionDefinition registerPolicy = defs.getAllValues().stream()
                .filter(d -> d.getCode().equals("paymaster.register-policy")).findFirst().orElseThrow();
        assertThat(all.get(0).getPermissionDefinitionId()).isEqualTo(registerPolicy.getId());
    }
}
