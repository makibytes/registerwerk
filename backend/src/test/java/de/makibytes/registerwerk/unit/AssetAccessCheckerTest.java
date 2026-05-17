package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransaction;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionRepository;
import de.makibytes.registerwerk.asset.web.AssetAccessChecker;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetAccessCheckerTest {

    AssetRepository assetRepository;
    AssetDeploymentRepository deploymentRepository;
    BlockchainTransactionRepository transactionRepository;
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
        transactionRepository = mock(BlockchainTransactionRepository.class);
        checker = new AssetAccessChecker(assetRepository, deploymentRepository, transactionRepository);

        Asset asset = new Asset();
        asset.setIssuerId(ISSUER_A);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        AssetDeployment dep = new AssetDeployment();
        dep.setAssetId(ASSET_ID);
        when(deploymentRepository.findById(DEP_ID)).thenReturn(Optional.of(dep));

        BlockchainTransaction tx = new BlockchainTransaction();
        tx.setAssetId(ASSET_ID);
        when(transactionRepository.findById(TX_ID)).thenReturn(Optional.of(tx));
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
        // imp:true token has entity_id of a different company but admin should still read anything
        assertThat(checker.canRead(ASSET_ID, impersonationAuth(ISSUER_B))).isTrue();
    }

    @Test
    void canActAsIssuer_impersonatingAdmin_returnsFalse() {
        // imp token must not grant issuer write access beyond the scoped entity
        assertThat(checker.canActAsIssuer(ASSET_ID, impersonationAuth(ISSUER_B))).isFalse();
    }

    @Test
    void canActAsIssuer_owningIssuer_returnsTrue() {
        assertThat(checker.canActAsIssuer(ASSET_ID, jwtAuth(ISSUER_A, "ISSUER"))).isTrue();
    }

    @Test
    void canActAsIssuer_admin_returnsFalse() {
        // Admin access is composed at the call site; this checker is pure issuer check
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
