package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.internal.AssetLifecycleService;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.internal.TradingService;
import de.makibytes.registerwerk.trading.web.dto.CreateTradeListingRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 / K3 against a real PostgreSQL: versioned quotes (partial unique index, stale-hash accept),
 * party gates and directory scoping, holdings / double-pledge (incl. trading seeing pledged units),
 * the redemption blocker, and the dispute round trip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Repo desk controls (integration)")
class RepoDeskIT {

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
        registry.add("registerwerk.repo-desk.enabled", () -> "true");
        registry.add("registerwerk.repo-desk.release-approved", () -> "true");
    }

    @MockitoBean ScreeningGate screeningGate;
    @MockitoBean FinalityGate finalityGate;

    @Autowired RepoDeskService desk;
    @Autowired RepoTradeService tradeService;
    @Autowired TradingService trading;
    @Autowired AssetLifecycleService lifecycle;
    @Autowired AssetRepository assets;
    @Autowired AssetHolderRepository holders;
    @Autowired LegalEntityRepository entities;
    @Autowired RepoTradeRepository trades;
    @Autowired RepoQuoteRepository quotes;
    @Autowired JdbcTemplate jdbc;

    private static final UUID USER = UUID.randomUUID();
    private static int leiCounter = 1000;

    private LegalEntity company(String tag, ClientCategory category) {
        LegalEntity e = new LegalEntity();
        e.setEntityNumber(tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        e.setType(EntityType.INVESTOR);
        e.setStatus(EntityStatus.ACTIVE);
        e.setCurrentName(tag + " test");
        e.setKycStatus(KycStatus.APPROVED);
        e.setClientCategory(category);
        e.setLeiCode(String.format("529900REPOIT%04d0000", leiCounter++ % 10000));
        return entities.saveAndFlush(e);
    }

    private Asset asset() {
        Asset a = new Asset();
        a.setAssetNumber("AST-" + UUID.randomUUID().toString().substring(0, 8));
        a.setIssuerId(company("ISS", ClientCategory.PROFESSIONAL).getId());
        a.setName("Repo IT");
        a.setTokenStandard(TokenStandard.ERC20);
        a.setStatus(AssetStatus.ISSUED);
        return assets.saveAndFlush(a);
    }

    private AssetHolder holder(Asset asset, LegalEntity owner, String nominal) {
        AssetHolder h = new AssetHolder();
        h.setAssetId(asset.getId());
        h.setInvestorId(owner.getId());
        h.setWalletAddress("0x" + UUID.randomUUID().toString().replace("-", "") + "00000000");
        h.setNominalAmount(new BigDecimal(nominal));
        h.setWhitelisted(true);
        h.setAcquisitionDate(LocalDate.now());
        return holders.saveAndFlush(h);
    }

    private RepoDeskService.CreateRfq rfq(Asset asset, String quantity, Side side) {
        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        return new RepoDeskService.CreateRfq(side, Visibility.BROADCAST, asset.getId(), new BigDecimal(quantity),
                new BigDecimal("50000"), "EUR", start, start.plusDays(30), null, null, SettlementMethod.DVP,
                Instant.now().plus(1, ChronoUnit.HOURS), Set.of(), null);
    }

    private RepoDeskService.SubmitQuote quote(String rate) {
        return new RepoDeskService.SubmitQuote(new BigDecimal("50000"), new BigDecimal(rate), 500,
                Instant.now().plus(30, ChronoUnit.MINUTES), null);
    }

    private record Desk(LegalEntity borrower, LegalEntity lender, Asset asset, AssetHolder holding) {}

    private Desk desk(String units) {
        LegalEntity borrower = company("BOR", ClientCategory.PROFESSIONAL);
        LegalEntity lender = company("LEN", ClientCategory.ELIGIBLE_COUNTERPARTY);
        Asset asset = asset();
        AssetHolder holding = holder(asset, borrower, units);
        desk.optIn(borrower.getId(), USER, true);
        desk.optIn(lender.getId(), USER, true);
        return new Desk(borrower, lender, asset, holding);
    }

    private RepoTrade accepted(Desk d, String units) {
        var view = desk.create(d.borrower().getId(), USER, rfq(d.asset(), units, Side.BORROW_CASH));
        UUID rfqId = view.rfq().getId();
        desk.submitQuote(rfqId, d.lender().getId(), USER, quote("4"));
        var seen = desk.get(rfqId, d.borrower().getId()).quotes().get(0);
        desk.acceptQuote(rfqId, seen.quote().getId(), d.borrower().getId(), seen.termsHash());
        return trades.findByRfqId(rfqId).orElseThrow();
    }

    @Test
    @DisplayName("re-quote after the requester viewed it: accept with the stale hash is refused, the new version is accepted")
    void staleQuoteHashIsRefused() {
        Desk d = desk("100");
        var created = desk.create(d.borrower().getId(), USER, rfq(d.asset(), "60", Side.BORROW_CASH));
        UUID rfqId = created.rfq().getId();
        desk.submitQuote(rfqId, d.lender().getId(), USER, quote("4"));
        var v1 = desk.get(rfqId, d.borrower().getId()).quotes().get(0);
        desk.submitQuote(rfqId, d.lender().getId(), USER, quote("9")); // re-quote right before accept

        assertThatThrownBy(() -> desk.acceptQuote(rfqId, v1.quote().getId(), d.borrower().getId(), v1.termsHash()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("superseded");

        List<RepoQuote> rows = quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfqId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(RepoQuote::getStatus).containsExactlyInAnyOrder(QuoteStatus.SUPERSEDED, QuoteStatus.ACTIVE);
        RepoQuote v2 = rows.stream().filter(q -> q.getStatus() == QuoteStatus.ACTIVE).findFirst().orElseThrow();
        assertThat(v2.getQuoteVersion()).isEqualTo(2);
        var v2View = desk.get(rfqId, d.borrower().getId()).quotes().stream().filter(q -> q.quote().getId().equals(v2.getId())).findFirst().orElseThrow();
        desk.acceptQuote(rfqId, v2.getId(), d.borrower().getId(), v2View.termsHash());
        RepoTrade trade = trades.findByRfqId(rfqId).orElseThrow();
        assertThat(trade.getRepoRate()).isEqualByComparingTo("9");
        assertThat(trade.getTermsHash()).isEqualTo(v2View.termsHash());
        assertThat(trade.getAcceptedQuoteVersion()).isEqualTo(2);
        assertThat(trade.getUti()).hasSize(52).startsWith(d.borrower().getLeiCode());
    }

    @Test
    @DisplayName("the database allows only one ACTIVE quote per counterparty and RFQ")
    void partialUniqueIndexOnActiveQuotes() {
        Desk d = desk("100");
        UUID rfqId = desk.create(d.borrower().getId(), USER, rfq(d.asset(), "10", Side.BORROW_CASH)).rfq().getId();
        desk.submitQuote(rfqId, d.lender().getId(), USER, quote("4"));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO repo_quote (id, rfq_id, quoting_entity_id, cash_amount, repo_rate, haircut_bps, valid_until, status, quote_version)
                VALUES (gen_random_uuid(), ?, ?, 1, 1, 0, now() + interval '1 hour', 'ACTIVE', 9)""", rfqId, d.lender().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("pledged units cannot be pledged again or listed for sale; trading sees the encumbrance")
    void noDoublePledgeAndTradingSeesEncumbrance() {
        Desk d = desk("100");
        RepoTrade trade = accepted(d, "60");

        // a second repo over 50 units: only 40 are unencumbered
        assertThatThrownBy(() -> desk.create(d.borrower().getId(), USER, rfq(d.asset(), "50", Side.BORROW_CASH)))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("unencumbered");
        // trading: 100 held, 60 pledged -> listing 50 must fail, 40 works
        assertThatThrownBy(() -> trading.createListing(d.borrower().getId(), USER, new CreateTradeListingRequest(
                d.holding().getId(), new BigDecimal("50"), BigDecimal.TEN, false, List.of(PaymentOption.OFFCHAIN_SEPA),
                false, "EUR", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("available for listing");
        trading.createListing(d.borrower().getId(), USER, new CreateTradeListingRequest(
                d.holding().getId(), new BigDecimal("40"), BigDecimal.TEN, false, List.of(PaymentOption.OFFCHAIN_SEPA),
                false, "EUR", null, null));
        // and the other way round: the units now listed are not available as collateral
        assertThatThrownBy(() -> desk.create(d.borrower().getId(), USER, rfq(d.asset(), "10", Side.BORROW_CASH)))
                .isInstanceOf(ComplianceGateException.class);
        assertThat(trade.getCollateralQuantity()).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("redemption is blocked while an open repo uses the asset as collateral")
    void redemptionBlockedWhileRepoOpen() {
        Desk d = desk("100");
        accepted(d, "20");
        assertThatThrownBy(() -> lifecycle.redeem(d.asset().getId(), "eWpG s.26", "REF-1", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("open repo");
    }

    @Test
    @DisplayName("participation and directory: only opted-in, listed, professional companies; retail and unlisted are invisible")
    void directoryIsOptInAndScoped() {
        Desk d = desk("10");
        LegalEntity retail = company("RET", ClientCategory.RETAIL);
        LegalEntity unlisted = company("UNL", ClientCategory.PROFESSIONAL);
        desk.optIn(unlisted.getId(), USER, false);
        LegalEntity notOptedIn = company("NOP", ClientCategory.PROFESSIONAL);

        List<UUID> visible = desk.counterparties(d.borrower().getId()).stream().map(RepoDeskService.CounterpartyView::id).toList();
        assertThat(visible).contains(d.lender().getId())
                .doesNotContain(retail.getId(), unlisted.getId(), notOptedIn.getId(), d.borrower().getId());
        assertThatThrownBy(() -> desk.optIn(retail.getId(), USER, true)).isInstanceOf(ComplianceGateException.class);
        assertThatThrownBy(() -> desk.counterparties(notOptedIn.getId())).hasMessageContaining("opted in");
        // collateral list only contains what the caller holds
        assertThat(desk.collateral(d.borrower().getId())).extracting(RepoDeskService.CollateralView::id).containsExactly(d.asset().getId());
        assertThat(desk.collateral(d.lender().getId())).isEmpty();
    }

    @Test
    @DisplayName("a dispute freezes the trade and the operator record returns it to its previous state")
    void disputeRoundTrip() {
        Desk d = desk("100");
        RepoTrade trade = accepted(d, "10");
        tradeService.openDispute(trade.getId(), d.borrower().getId(), USER, "cash not received");
        assertThat(trades.findById(trade.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertThat(tradeService.disputes()).extracting(v -> v.trade().getId()).contains(trade.getId());
        // still counted as encumbered while disputed
        assertThatThrownBy(() -> desk.create(d.borrower().getId(), USER, rfq(d.asset(), "95", Side.BORROW_CASH)))
                .isInstanceOf(ComplianceGateException.class);
        tradeService.resolveDispute(trade.getId(), USER, DisputeResolution.CANCEL, "Parties agreed to cancel", null, UUID.randomUUID());
        assertThat(trades.findById(trade.getId()).orElseThrow().getStatus()).isEqualTo(TradeStatus.CANCELLED);
        // cancelled -> encumbrance released
        desk.create(d.borrower().getId(), USER, rfq(d.asset(), "95", Side.BORROW_CASH));
    }

    @Test
    @DisplayName("an unregistered LEI blocks acceptance")
    void missingLeiBlocksAcceptance() {
        Desk d = desk("100");
        d.lender().setLeiCode(null);
        entities.saveAndFlush(d.lender());
        UUID rfqId = desk.create(d.borrower().getId(), USER, rfq(d.asset(), "10", Side.BORROW_CASH)).rfq().getId();
        desk.submitQuote(rfqId, d.lender().getId(), USER, quote("4"));
        var seen = desk.get(rfqId, d.borrower().getId()).quotes().get(0);
        assertThatThrownBy(() -> desk.acceptQuote(rfqId, seen.quote().getId(), d.borrower().getId(), seen.termsHash()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LEI");
    }
}
