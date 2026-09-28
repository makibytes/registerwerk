package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.ForcedOpTargetGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T3-21: a grantee-initiated forced op may only target an active register wallet of that asset. */
@DisplayName("ForcedOpTargetGuard")
class ForcedOpTargetGuardTest {

    private final AssetHolderRepository holders = mock(AssetHolderRepository.class);
    private final ForcedOpTargetGuard guard = new ForcedOpTargetGuard(holders);
    private final UUID assetId = UUID.randomUUID();

    @Test
    @DisplayName("grantee targeting a wallet outside the asset's register → 400")
    void granteeForeignWalletRefused() {
        when(holders.findActiveByAssetIdAndWalletAddress(assetId, "0x" + "ab".repeat(20))).thenReturn(Optional.empty());
        var grantee = new TestingAuthenticationToken("u", "p", "ROLE_ISSUER");

        assertThatThrownBy(() -> guard.requireHolderWalletForGrantee(assetId, "0x" + "AB".repeat(20), grantee))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("grantee targeting an active holder (checksum-cased) is allowed")
    void granteeHolderAllowed() {
        when(holders.findActiveByAssetIdAndWalletAddress(assetId, "0x" + "ab".repeat(20)))
                .thenReturn(Optional.of(new AssetHolder()));
        var grantee = new TestingAuthenticationToken("u", "p", "ROLE_ISSUER");

        assertThatCode(() -> guard.requireHolderWalletsForGrantee(assetId, List.of("0x" + "AB".repeat(20)), grantee))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("REGISTRY_ADMIN is not restricted")
    void registryAdminUnrestricted() {
        var admin = new TestingAuthenticationToken("u", "p", "ROLE_REGISTRY_ADMIN");

        guard.requireHolderWalletForGrantee(assetId, "0xanything", admin);

        verify(holders, never()).findActiveByAssetIdAndWalletAddress(any(), any());
    }
}
