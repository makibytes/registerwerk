package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.schedule.Target2Calendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-04 end to end against a real database: operator enters bond terms → the schedule rows exist
 * with ICMA amounts and TARGET2 record dates → {@link CouponPaymentJob} raises a COUPON action.
 * The previous mock-only job test fed hand-made SCHEDULED rows, hiding that nothing in the
 * application ever wrote one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Coupon schedule generation integration test")
class CouponScheduleServiceIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

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

    @Autowired TestRestTemplate rest;
    @Autowired AssetRepository assetRepository;
    @Autowired LegalEntityRepository legalEntityRepository;
    @Autowired AssetCouponPaymentRepository couponPaymentRepository;
    @Autowired CorporateActionRepository corporateActionRepository;
    @Autowired CouponPaymentJob couponPaymentJob;
    @LocalServerPort int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders headers(boolean stepUp) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(TestJwt.mint(SECRET, OPERATOR, stepUp, null, null, "REGISTRY_ADMIN"));
        return h;
    }

    private Asset asset(AssetStatus status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity issuer = new LegalEntity();
        issuer.setEntityNumber("ISS-" + suffix);
        issuer.setType(EntityType.ISSUER);
        issuer.setStatus(EntityStatus.ACTIVE);
        issuer.setCurrentName("Schedule Test Issuer");
        issuer = legalEntityRepository.saveAndFlush(issuer);
        Asset asset = new Asset();
        asset.setAssetNumber("AST-" + suffix);
        asset.setIssuerId(issuer.getId());
        asset.setName("4 % Schedule Test Bond");
        asset.setTokenStandard(TokenStandard.ERC3525);
        asset.setStatus(status);
        return assetRepository.saveAndFlush(asset);
    }

    private Map<String, Object> terms(LocalDate issue, LocalDate maturity) {
        return Map.of(
                "faceValue", 1000, "currencyIso", "EUR",
                "issueDate", issue.toString(), "maturityDate", maturity.toString(),
                "couponRate", 0.04, "paymentFrequency", "ANNUAL", "callable", false,
                // NONE keeps the final payment on today even when the test runs on a weekend.
                "businessDayConvention", "NONE");
    }

    @Test
    void bondTermsGenerateScheduleAndCouponJobRaisesAction() {
        LocalDate today = LocalDate.now();
        Asset asset = asset(AssetStatus.APPROVED);
        String path = "/api/v1/assets/" + asset.getId() + "/bond-terms";

        // Upsert is step-up gated.
        assertThat(rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(terms(today.minusYears(2), today),
                headers(false)), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> saved = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(terms(today.minusYears(2), today), headers(true)), String.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).contains("\"dayCount\":\"ACT_ACT_ICMA\"");

        // Only future payment dates are generated: the coupon due a year ago is not back-filled.
        List<AssetCouponPayment> rows = couponPaymentRepository.findByAssetIdOrderByPeriodNo(asset.getId());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getPeriodNo()).isEqualTo(2);
            assertThat(row.getScheduledDate()).isEqualTo(today);
            assertThat(row.getPeriodStart()).isEqualTo(today.minusYears(1));
            assertThat(row.getRecordDate()).isEqualTo(Target2Calendar.INSTANCE.addBusinessDays(today, -1));
            assertThat(row.getAnnouncementDate()).isEqualTo(Target2Calendar.INSTANCE.addBusinessDays(today, -6));
            assertThat(row.getDayCountFraction()).isEqualByComparingTo("1");
            assertThat(row.getAmountPerUnit()).isEqualByComparingTo(new BigDecimal("40"));
            assertThat(row.getCouponStatus()).isEqualTo(CouponStatus.SCHEDULED);
        });

        ResponseEntity<String> schedule = rest.exchange(url(path + "/schedule"), HttpMethod.GET,
                new HttpEntity<>(headers(false)), String.class);
        assertThat(schedule.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(schedule.getBody()).contains("\"paymentDate\":\"" + today + "\"");

        asset.setStatus(AssetStatus.ISSUED);
        assetRepository.saveAndFlush(asset);
        couponPaymentJob.processDuePayments();

        assertThat(corporateActionRepository.existsByCouponPaymentId(rows.get(0).getId())).isTrue();

        // After issuance the wholesale upsert is refused; terms change only by amendment.
        ResponseEntity<String> locked = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(terms(today.minusYears(2), today), headers(true)), String.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(locked.getBody()).contains("terms-amendments");
    }

    @Test
    void regenerationReplacesOnlyUnraisedFutureRows() {
        LocalDate today = LocalDate.now();
        Asset asset = asset(AssetStatus.APPROVED);
        String path = "/api/v1/assets/" + asset.getId() + "/bond-terms";
        rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(terms(today.minusYears(1), today.plusYears(3)),
                headers(true)), String.class);
        List<AssetCouponPayment> first = couponPaymentRepository.findByAssetIdOrderByPeriodNo(asset.getId());
        // today, +1y, +2y, +3y (the period ending today is due today, so it is still generated)
        assertThat(first).hasSize(4);
        AssetCouponPayment paid = first.get(1);
        paid.setCouponStatus(CouponStatus.PAID);
        couponPaymentRepository.saveAndFlush(paid);

        // Same terms again: unraised future rows are replaced by version 2; the PAID row is history.
        rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(terms(today.minusYears(1), today.plusYears(3)),
                headers(true)), String.class);
        List<AssetCouponPayment> second = couponPaymentRepository.findByAssetIdOrderByPeriodNo(asset.getId());
        assertThat(second).hasSize(3);
        assertThat(second).filteredOn(r -> r.getId().equals(paid.getId())).singleElement()
                .satisfies(r -> assertThat(r.getScheduleVersion()).isEqualTo(1));
        // Periods up to the kept row are not regenerated; later ones are, as version 2.
        assertThat(second).filteredOn(r -> !r.getId().equals(paid.getId()))
                .extracting(AssetCouponPayment::getScheduledDate)
                .containsExactly(today.plusYears(2), today.plusYears(3));
        assertThat(second).filteredOn(r -> !r.getId().equals(paid.getId()))
                .allSatisfy(r -> assertThat(r.getScheduleVersion()).isEqualTo(2));
    }
}
