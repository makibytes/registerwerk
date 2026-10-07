package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.internal.TermSheetService;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.travelrule.internal.WalletControlProofService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 5b item 10: after the K6 gates the demo data must carry the evidence the gates ask for. Seeds minimal
 * "base seeder" rows (APPROVED entity, ISSUED asset, registered holder wallet) and checks that the coherence seeder
 * adds approval history, a public term sheet and a valid wallet-control proof - idempotently and only where missing.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Demo coherence seeder")
class DemoCoherenceSeederIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.dev-secret", () -> "integration-test-jwt-secret-32-bytes!!");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!Pass");
    }

    @Autowired LegalEntityRepository entities;
    @Autowired AssetRepository assets;
    @Autowired TermSheetService termSheets;
    @Autowired WalletControlProofService proofs;
    @Autowired JdbcTemplate jdbc;

    private LegalEntity entity(String number, KycStatus kyc) {
        LegalEntity e = new LegalEntity();
        e.setEntityNumber(number);
        e.setType(EntityType.ISSUER);
        e.setStatus(EntityStatus.ACTIVE);
        e.setCurrentName("Demo " + number);
        e.setKycStatus(kyc);
        e.setKycExpiryDate(LocalDate.now().plusYears(1));
        return entities.saveAndFlush(e);
    }

    private Asset asset(LegalEntity issuer, String number, AssetStatus status) {
        Asset a = new Asset();
        a.setAssetNumber(number);
        a.setIssuerId(issuer.getId());
        a.setName("Demo bond " + number);
        a.setTokenStandard(TokenStandard.ERC3525);
        a.setStatus(status);
        return assets.saveAndFlush(a);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void seedsTheEvidenceTheGatesAskForIdempotentlyAndOnlyWhereMissing() {
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        LegalEntity approved = entity("DEMO-A-" + sfx, KycStatus.APPROVED);
        LegalEntity pending = entity("DEMO-P-" + sfx, KycStatus.NOT_STARTED);
        Asset issued = asset(approved, "DEMO-I-" + sfx, AssetStatus.ISSUED);
        Asset draft = asset(approved, "DEMO-D-" + sfx, AssetStatus.DRAFT);
        String wallet = "0x" + "ab".repeat(20);
        jdbc.update("INSERT INTO asset_holder (asset_id, investor_id, wallet_address, nominal_amount) VALUES (?,?,?,?)",
                issued.getId(), approved.getId(), wallet, 10);

        assertThat(termSheets.publicTermSheet(issued)).as("no public term sheet before seeding").isEmpty();
        assertThat(proofs.findValidProof(issued.getId(), wallet)).isEmpty();

        DemoCoherenceSeeder seeder = new DemoCoherenceSeeder(jdbc);
        int[] first = seeder.seed();

        assertThat(first[0]).isGreaterThanOrEqualTo(1);
        assertThat(first[1]).isGreaterThanOrEqualTo(1);
        assertThat(first[2]).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM kyc_approval_record WHERE entity_id = ?", approved.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM kyc_approval_record WHERE entity_id = ?", pending.getId())).isZero();
        assertThat(termSheets.publicTermSheet(issued)).as("public term sheet gate").isPresent();
        assertThat(count("SELECT count(*) FROM asset_document WHERE asset_id = ?", draft.getId())).isZero();
        assertThat(proofs.findValidProof(issued.getId(), wallet)).as("Travel Rule wallet-control gate").isPresent();

        int[] second = seeder.seed();

        assertThat(second).containsExactly(0, 0, 0);
        assertThat(count("SELECT count(*) FROM kyc_approval_record WHERE entity_id = ?", approved.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM asset_document WHERE asset_id = ? AND document_type = 'TERM_SHEET'",
                issued.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM wallet_control_proof WHERE legal_entity_id = ?", approved.getId()))
                .isEqualTo(1);
    }
}
