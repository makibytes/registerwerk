package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-08 against a real database (V15 schema, JPA mapping, unique wallet index): the full off-chain
 * flow SUBMITTED -> ALLOCATED -> accepted -> PAYMENT_CONFIRMED -> SETTLED, a top-up on the same wallet
 * (which used to hit the unique index), and the lapse job's query.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Primary subscription settlement (integration)")
class SubscriptionSettlementIT {

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

    private static final String WALLET = "0x" + "b".repeat(40);

    /** A never-screened entity is fail-closed (unresolved) by the real gate; screening itself is not under test. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    de.makibytes.registerwerk.screening.api.ScreeningGate screeningGate;

    @Autowired SubscriptionOrderService service;
    @Autowired SubscriptionOrderRepository orders;
    @Autowired AssetRepository assets;
    @Autowired AssetHolderRepository holders;
    @Autowired LegalEntityRepository entities;

    @Test
    @DisplayName("settle + top-up: the second settlement increments the existing row instead of hitting the unique wallet index")
    void topUpIncrementsExistingRow() {
        LegalEntity investor = entity("INV", KycStatus.APPROVED);
        Asset asset = asset();

        SubscriptionOrder first = paid(asset, investor, "100");
        service.settle(first.getId(), null, "REGISTRY_ADMIN");
        SubscriptionOrder second = paid(asset, investor, "50");
        SubscriptionOrder settled = service.settle(second.getId(), null, "REGISTRY_ADMIN");

        assertThat(settled.getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLED);
        assertThat(holders.findActiveByAssetId(asset.getId())).hasSize(1);
        AssetHolder holder = holders.findActiveByAssetId(asset.getId()).get(0);
        assertThat(holder.getNominalAmount()).isEqualByComparingTo("150");
        assertThat(orders.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(SubscriptionOrder.Status.SETTLED);
    }

    @Test
    @DisplayName("settle refuses an investor whose KYC is not approved; nothing enters the register")
    void settleRefusedWithoutKyc() {
        LegalEntity investor = entity("INV", KycStatus.EXPIRED);
        Asset asset = asset();
        SubscriptionOrder order = paid(asset, investor, "10");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.settle(order.getId(), null, "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class);
        assertThat(holders.findActiveByAssetId(asset.getId())).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionOrder.Status.PAYMENT_CONFIRMED);
    }

    @Test
    @DisplayName("lapse job query: only ALLOCATED orders past their deadline lapse and free capacity")
    void allocationLapsesAndReleasesCapacity() {
        LegalEntity investor = entity("INV", KycStatus.APPROVED);
        Asset asset = asset();
        SubscriptionOrder order = service.submit(asset.getId(), investor.getId(), WALLET, new BigDecimal("40"), null, "INVESTOR");
        service.allocate(order.getId(), new BigDecimal("40"), null, "REGISTRY_ADMIN");
        assertThat(orders.sumAllocated(asset.getId())).isEqualByComparingTo("40");

        SubscriptionOrder stored = orders.findById(order.getId()).orElseThrow();
        stored.setAllocationExpiresAt(Instant.now().minusSeconds(60));
        orders.saveAndFlush(stored);

        assertThat(service.lapseExpiredAllocations(Instant.now(), 100)).isGreaterThanOrEqualTo(1);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(SubscriptionOrder.Status.LAPSED);
        assertThat(orders.sumAllocated(asset.getId())).isEqualByComparingTo("0");
    }

    private SubscriptionOrder paid(Asset asset, LegalEntity investor, String amount) {
        SubscriptionOrder o = service.submit(asset.getId(), investor.getId(), WALLET, new BigDecimal(amount), null, "INVESTOR");
        service.allocate(o.getId(), new BigDecimal(amount), null, "REGISTRY_ADMIN");
        service.accept(o.getId(), null, "INVESTOR");
        return service.confirmPayment(o.getId(), new BigDecimal(amount), "PAY-" + UUID.randomUUID(), null, null, "REGISTRY_ADMIN");
    }

    private LegalEntity entity(String tag, KycStatus kyc) {
        LegalEntity e = new LegalEntity();
        e.setEntityNumber(tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        e.setType(EntityType.INVESTOR);
        e.setStatus(EntityStatus.ACTIVE);
        e.setCurrentName(tag + " test");
        e.setKycStatus(kyc);
        return entities.saveAndFlush(e);
    }

    private Asset asset() {
        Asset a = new Asset();
        a.setAssetNumber("AST-" + UUID.randomUUID().toString().substring(0, 8));
        a.setIssuerId(entity("ISS", KycStatus.APPROVED).getId());
        a.setName("Subscription IT");
        a.setTokenStandard(TokenStandard.ERC20);
        a.setStatus(AssetStatus.ISSUED);
        return assets.saveAndFlush(a);
    }
}
