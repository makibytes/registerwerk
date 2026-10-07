package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrant;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review phase 3, K1 against a real database: T3-15 Sperrvermerk wallet normalisation (write path,
 * gate) and T3-21 entity-wide ASSET_TOKEN_ADMIN grant scope.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Sperrvermerk normalisation + token-admin grant scope (integration)")
class HolderBlockNormalisationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired SperrvermerkService service;
    @Autowired HolderBlockGate gate;
    @Autowired LegalEntityRepository legalEntityRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired AssetTokenAdminGrantRepository grantRepository;

    @Test
    @DisplayName("checksumAddressBlockFreezesAndGates: stored lowercase, gate matches every casing")
    void checksumAddressBlockFreezesAndGates() {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        String checksum = "0xDeAdBeEf" + hex.toUpperCase();

        HolderBlock block = new HolderBlock();
        block.setWalletAddress(" " + checksum);
        block.setBlockType(HolderBlock.BlockType.GERICHTSBESCHLUSS);
        block.setLegalBasis("LG Frankfurt 2-04 O 1/26");
        HolderBlock saved = service.create(block, UUID.randomUUID(), "REGISTRY_ADMIN", UUID.randomUUID());

        assertThat(saved.getWalletAddress()).isEqualTo(checksum.toLowerCase());
        assertThat(gate.isBlocked(null, checksum)).isTrue();
        assertThat(gate.isBlocked(null, checksum.toLowerCase())).isTrue();
        assertThat(service.findActiveByWallet(checksum)).hasSize(1);
    }

    @Test
    @DisplayName("entityWideGrantDoesNotCoverOtherIssuersAsset")
    void entityWideGrantDoesNotCoverOtherIssuersAsset() {
        LegalEntity grantee = entity("GRANTEE");
        LegalEntity otherIssuer = entity("OTHER");
        Asset ownAsset = asset(grantee.getId());
        Asset foreignAsset = asset(otherIssuer.getId());

        AssetTokenAdminGrant g = new AssetTokenAdminGrant();
        g.setEntityId(grantee.getId());
        g.setAssetId(null); // entity-wide
        g.setWalletAddress("0x" + "1".repeat(40));
        g.setEligibilityBasis(AssetTokenAdminGrant.EligibilityBasis.ISSUER_WALLET_BINDING);
        g.setLegalBasis("delegation");
        g.setCreatedBy(UUID.randomUUID());
        grantRepository.saveAndFlush(g);

        Instant now = Instant.now();
        assertThat(grantRepository.existsActiveForEntityAndAsset(grantee.getId(), ownAsset.getId(), now)).isTrue();
        assertThat(grantRepository.existsActiveForEntityAndAsset(grantee.getId(), foreignAsset.getId(), now)).isFalse();
    }

    private LegalEntity entity(String tag) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity e = new LegalEntity();
        e.setEntityNumber(tag.substring(0, 3) + "-" + suffix);
        e.setType(EntityType.ISSUER);
        e.setStatus(EntityStatus.ACTIVE);
        e.setCurrentName(tag + " issuer");
        return legalEntityRepository.saveAndFlush(e);
    }

    private Asset asset(UUID issuerId) {
        Asset a = new Asset();
        a.setAssetNumber("AST-" + UUID.randomUUID().toString().substring(0, 8));
        a.setIssuerId(issuerId);
        a.setName("Grant scope test");
        a.setTokenStandard(TokenStandard.ERC20);
        return assetRepository.saveAndFlush(a);
    }
}
