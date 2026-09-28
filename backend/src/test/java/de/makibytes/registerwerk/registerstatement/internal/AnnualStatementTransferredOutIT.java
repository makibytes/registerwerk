package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-07 against a real database: the annual §19 statement queue skips TRANSFERRED_OUT assets
 * (the successor registrar issues those now), and the asset row accepts the two handover statuses
 * (V14 - the V1 CHECK constraint never listed TRANSFERRED_OUT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Annual statement queue vs. transferred-out register (integration)")
class AnnualStatementTransferredOutIT {

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

    @Autowired AssetRepository assetRepository;
    @Autowired AssetHolderRepository holderRepository;
    @Autowired LegalEntityRepository legalEntityRepository;

    @Test
    @DisplayName("annualStatementSkipsTransferredOut")
    void annualStatementSkipsTransferredOut() {
        LegalEntity investor = entity("INV");
        Asset live = asset(AssetStatus.ISSUED);
        Asset pending = asset(AssetStatus.TRANSFER_PENDING);
        Asset out = asset(AssetStatus.TRANSFERRED_OUT);
        AssetHolder liveHolder = holder(live, investor);
        AssetHolder pendingHolder = holder(pending, investor);
        AssetHolder outHolder = holder(out, investor);

        var due = holderRepository.findAnnualStatementDueFirst(Instant.now(), PageRequest.of(0, 1000));

        assertThat(due).extracting(AssetHolder::getId).contains(liveHolder.getId(), pendingHolder.getId());
        assertThat(due).extracting(AssetHolder::getId).doesNotContain(outHolder.getId());
        var after = holderRepository.findAnnualStatementDueAfter(Instant.now(), new UUID(0, 0), PageRequest.of(0, 1000));
        assertThat(after).extracting(AssetHolder::getId).doesNotContain(outHolder.getId());
    }

    private LegalEntity entity(String tag) {
        LegalEntity e = new LegalEntity();
        e.setEntityNumber(tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        e.setType(EntityType.INVESTOR);
        e.setStatus(EntityStatus.ACTIVE);
        e.setCurrentName(tag + " test");
        return legalEntityRepository.saveAndFlush(e);
    }

    private Asset asset(AssetStatus status) {
        Asset a = new Asset();
        a.setAssetNumber("AST-" + UUID.randomUUID().toString().substring(0, 8));
        a.setIssuerId(entity("ISS").getId());
        a.setName("Annual statement test");
        a.setTokenStandard(TokenStandard.ERC20);
        a.setStatus(status);
        return assetRepository.saveAndFlush(a);
    }

    private AssetHolder holder(Asset asset, LegalEntity investor) {
        AssetHolder h = new AssetHolder();
        h.setAssetId(asset.getId());
        h.setInvestorId(investor.getId());
        h.setWalletAddress("0x" + UUID.randomUUID().toString().replace("-", "") + "00000000");
        h.setNominalAmount(BigDecimal.TEN);
        h.setEntryType(EntryType.INDIVIDUAL);
        h.setIsConsumer(true);
        h.setHolderReference("RW-" + UUID.randomUUID().toString().substring(0, 8));
        return holderRepository.saveAndFlush(h);
    }
}
