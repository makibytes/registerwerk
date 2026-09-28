package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
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

import java.math.BigDecimal;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-04 end to end against a real database: operator enters bond terms → the schedule rows exist
 * with ICMA amounts and TARGET2 record dates → {@link CouponPaymentJob} raises a COUPON action.
 * The previous mock-only job test fed hand-made SCHEDULED rows, hiding that nothing in the
 * application ever wrote one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Position history (V13) and record-date resolution integration test")
class RecordDatePositionHistoryIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.dev-secret", () -> SECRET);
    }

    @Autowired AssetRepository assetRepository;
    @Autowired LegalEntityRepository legalEntityRepository;
    @Autowired AssetHolderRepository holderRepository;
    @Autowired RecordDatePositionResolver resolver;
    @Autowired HolderPositionHistoryReader reader;

    @Test
    void offchainRegisterIsResolvedAsOfTheRecordDateNotLive() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity issuer = new LegalEntity();
        issuer.setEntityNumber("ISS-" + suffix);
        issuer.setType(EntityType.ISSUER);
        issuer.setStatus(EntityStatus.ACTIVE);
        issuer.setCurrentName("History Issuer");
        issuer = legalEntityRepository.saveAndFlush(issuer);
        Asset asset = new Asset();
        asset.setAssetNumber("AST-" + suffix);
        asset.setIssuerId(issuer.getId());
        asset.setName("History Bond");
        asset.setTokenStandard(TokenStandard.ERC3525);
        asset.setStatus(AssetStatus.ISSUED);
        asset = assetRepository.saveAndFlush(asset);

        AssetHolder holder = new AssetHolder();
        holder.setAssetId(asset.getId());
        holder.setInvestorId(issuer.getId());
        holder.setWalletAddress("0x" + "ab".repeat(20));
        holder.setNominalAmount(new BigDecimal("100"));
        holder = holderRepository.saveAndFlush(holder);

        Thread.sleep(50);
        Instant cutoff = Instant.now();
        Thread.sleep(50);

        // The position changes after the cut-off (a later trade / correction).
        holder = holderRepository.findById(holder.getId()).orElseThrow();
        holder.setNominalAmount(new BigDecimal("30"));
        holder = holderRepository.saveAndFlush(holder);

        var atCutoff = resolver.resolve(asset.getId(), cutoff);
        assertThat(atCutoff.blockedReason()).isEmpty();
        assertThat(atCutoff.positions()).singleElement()
                .satisfies(p -> assertThat(p.nominal()).isEqualByComparingTo("100"));

        var now = resolver.resolve(asset.getId(), Instant.now().plusSeconds(1));
        assertThat(now.positions()).singleElement()
                .satisfies(p -> assertThat(p.nominal()).isEqualByComparingTo("30"));

        // A removal after the cut-off does not remove the holder from the earlier record date.
        holder = holderRepository.findById(holder.getId()).orElseThrow();
        holder.setRemovedAt(Instant.now());
        holderRepository.saveAndFlush(holder);
        assertThat(resolver.resolve(asset.getId(), cutoff).positions()).hasSize(1);
        assertThat(resolver.resolve(asset.getId(), Instant.now().plusSeconds(1)).positions()).isEmpty();
        assertThat(reader.positionsAsOf(asset.getId(), cutoff)).hasSize(1);
    }
}
