package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.web.AssetAccessChecker;
import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetAccessCheckerTest {

    AssetRepository assetRepository;
    AssetDeploymentRepository deploymentRepository;
    AssetHolderRepository holderRepository;
    BlockchainApi blockchainApi;
    AssetTokenAdminGrantRepository tokenAdminGrantRepository;
    AssetAccessChecker checker;

    static final UUID ISSUER_A = UUID.randomUUID();
    static final UUID ISSUER_B = UUID.randomUUID();
    static final UUID ASSET_ID = UUID.randomUUID();
    static final UUID DEP_ID   = UUID.randomUUID();
    static final UUID TX_ID    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assetRepository      = mock(AssetRepository.class);
        deploymentRepository = mock(AssetDeploymentRepository.class);
        holderRepository     = mock(AssetHolderRepository.class);
        blockchainApi        = mock(BlockchainApi.class);
        tokenAdminGrantRepository = mock(AssetTokenAdminGrantRepository.class);
        checker = new AssetAccessChecker(
                assetRepository, deploymentRepository, holderRepository, blockchainApi, tokenAdminGrantRepository);

        Asset asset = new Asset();
        asset.setIssuerId(ISSUER_A);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        AssetDeployment dep = new AssetDeployment();
        dep.setAssetId(ASSET_ID);
        when(deploymentRepository.findById(DEP_ID)).thenReturn(Optional.of(dep));

        BlockchainTransactionView txView = new BlockchainTransactionView(
                TX_ID, null, "SUCCESS", null, null, null, null,
                DEP_ID, ASSET_ID, null, null, null, null, null, null,
                Instant.now(), null);
        when(blockchainApi.findTransaction(TX_ID)).thenReturn(Optional.of(txView));
    }

    @Test
    void canRead_owningIssuer_returnsTrue() {
        assertThat(checker.canRead(ASSET_ID, jwtAuth(ISSUER_A, "ISSUER"))).isTrue();
    }

    @Test
    void canRead_differentIssuer_returnsFalse() {
        assertThat(checker.canRead(ASSET_ID, jwtAuth(ISSUER_B, "ISSUER"))).isFalse();
    }

    @Test
    void canRead_admin_returnsTrue() {
        assertThat(checker.canRead(ASSET_ID, jwtAuth(ISSUER_B, "REGISTRY_ADMIN"))).isTrue();
    }

    @Test
    void canRead_impersonatingAdmin_returnsTrue() {
        assertThat(checker.canRead(ASSET_ID, impersonationAuth(ISSUER_B))).isTrue();
    }

    @Test
    void canActAsIssuer_impersonatingAdmin_returnsFalse() {
        assertThat(checker.canActAsIssuer(ASSET_ID, impersonationAuth(ISSUER_B))).isFalse();
    }

    @Test
    void canActAsIssuer_owningIssuer_returnsTrue() {
        assertThat(checker.canActAsIssuer(ASSET_ID, jwtAuth(ISSUER_A, "ISSUER"))).isTrue();
    }

    @Test
    void canActAsIssuer_admin_returnsFalse() {
        assertThat(checker.canActAsIssuer(ASSET_ID, jwtAuth(ISSUER_B, "REGISTRY_ADMIN"))).isFalse();
    }

    @Test
    void canActAsIssuer_differentIssuer_returnsFalse() {
        assertThat(checker.canActAsIssuer(ASSET_ID, jwtAuth(ISSUER_B, "ISSUER"))).isFalse();
    }

    @Test
    void canReadTransaction_owningIssuer_returnsTrue() {
        assertThat(checker.canReadTransaction(TX_ID, jwtAuth(ISSUER_A, "ISSUER"))).isTrue();
    }

    @Test
    void canReadTransaction_differentIssuer_returnsFalse() {
        assertThat(checker.canReadTransaction(TX_ID, jwtAuth(ISSUER_B, "ISSUER"))).isFalse();
    }

    @Test
    void canReadDeployment_owningIssuer_returnsTrue() {
        assertThat(checker.canReadDeployment(DEP_ID, jwtAuth(ISSUER_A, "ISSUER"))).isTrue();
    }

    @Test
    void canReadDeployment_differentIssuer_returnsFalse() {
        assertThat(checker.canReadDeployment(DEP_ID, jwtAuth(ISSUER_B, "ISSUER"))).isFalse();
    }

    @Test
    void nullAuthentication_returnsFalse() {
        assertThat(checker.canRead(ASSET_ID, null)).isFalse();
        assertThat(checker.canActAsIssuer(ASSET_ID, null)).isFalse();
    }

    // ── canForceAdmin (ASSET_TOKEN_ADMIN delegation) ──────────────────────────

    @Test
    void canForceAdmin_activeGrant_returnsTrue() {
        when(tokenAdminGrantRepository.existsActiveForEntityAndAsset(eq(ISSUER_A), eq(ASSET_ID), any()))
                .thenReturn(true);
        assertThat(checker.canForceAdmin(ASSET_ID, jwtAuth(ISSUER_A, "ISSUER"))).isTrue();
    }

    @Test
    void canForceAdmin_noGrant_returnsFalse() {
        when(tokenAdminGrantRepository.existsActiveForEntityAndAsset(eq(ISSUER_A), eq(ASSET_ID), any()))
                .thenReturn(false);
        assertThat(checker.canForceAdmin(ASSET_ID, jwtAuth(ISSUER_A, "ISSUER"))).isFalse();
    }

    @Test
    void canForceAdmin_owningIssuerWithoutGrant_stillReturnsFalse() {
        // Confirms the tightening: asset ownership alone is no longer sufficient.
        when(tokenAdminGrantRepository.existsActiveForEntityAndAsset(any(), any(), any())).thenReturn(false);
        assertThat(checker.canForceAdmin(ASSET_ID, jwtAuth(ISSUER_A, "ISSUER"))).isFalse();
    }

    @Test
    void canForceAdmin_nullAuthentication_returnsFalse() {
        assertThat(checker.canForceAdmin(ASSET_ID, null)).isFalse();
    }

    // ── isHolderOfAsset (confidential-context read access) ────────────────────

    @Test
    void isHolderOfAsset_holderWithPosition_returnsTrue() {
        UUID investor = UUID.randomUUID();
        when(holderRepository.existsActiveByAssetIdAndInvestorId(ASSET_ID, investor)).thenReturn(true);
        assertThat(checker.isHolderOfAsset(ASSET_ID, jwtAuth(investor, "INVESTOR"))).isTrue();
    }

    @Test
    void isHolderOfAsset_noPosition_returnsFalse() {
        UUID investor = UUID.randomUUID();
        when(holderRepository.existsActiveByAssetIdAndInvestorId(ASSET_ID, investor)).thenReturn(false);
        assertThat(checker.isHolderOfAsset(ASSET_ID, jwtAuth(investor, "INVESTOR"))).isFalse();
    }

    @Test
    void isHolderOfAsset_nullAuthentication_returnsFalse() {
        assertThat(checker.isHolderOfAsset(ASSET_ID, null)).isFalse();
    }

    @Test
    void canForceAdmin_noEntityId_returnsFalse() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("sub", UUID.randomUUID().toString())
                .build();
        Authentication auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_REGISTRY_ADMIN")));
        assertThat(checker.canForceAdmin(ASSET_ID, auth)).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Authentication jwtAuth(UUID entityId, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("entity_id", entityId.toString())
                .claim("sub", entityId.toString())
                .build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Authentication impersonationAuth(UUID entityId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("entity_id", entityId.toString())
                .claim("sub", UUID.randomUUID().toString())
                .claim("imp", true)
                .build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ISSUER"),
                        new SimpleGrantedAuthority("ROLE_COMPANY_ADMIN")));
    }
}
