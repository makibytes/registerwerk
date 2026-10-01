package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.events.AssetSuspendedEvent;
import de.makibytes.registerwerk.asset.internal.HolderService;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.OrderType;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.api.WalletPreferenceMode;
import de.makibytes.registerwerk.trading.web.dto.BuyTradingOfferRequest;
import de.makibytes.registerwerk.trading.web.dto.CreateTradeListingRequest;
import de.makibytes.registerwerk.trading.web.dto.TradeExecutionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 / K1 against a real PostgreSQL (row locks, the V26 status check constraint, optimistic
 * version): gates before reservation, reservation caps, the timeout-vs-confirm lost update,
 * register removal / asset status invalidating listings, PAYMENT_UNRESOLVED and its operator queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Trading core (integration)")
class TradingCoreIT {

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

    /** Screening itself is not under test; a never-screened entity is fail-closed with the real gate. */
    @MockitoBean ScreeningGate screeningGate;
    /** Finality/chain-quarantine state is not under test either. */
    @MockitoBean FinalityGate finalityGate;

    @Autowired TradingService trading;
    @Autowired TradeQueueService queue;
    @Autowired TradeTimeoutProcessor timeoutProcessor;
    @Autowired TradeTimeoutJob timeoutJob;
    @Autowired HolderService holderService;
    @Autowired TradeListingRepository listings;
    @Autowired TradeExecutionRepository executions;
    @Autowired AssetRepository assets;
    @Autowired AssetHolderRepository holders;
    @Autowired LegalEntityRepository entities;
    @Autowired AddressEndpointRepository endpoints;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final UUID ACTOR = UUID.randomUUID();

    // ── scenario builders ───────────────────────────────────────────────────

    private record Market(Asset asset, LegalEntity seller, AssetHolder sellerHolder, TradeListing listing) {}
    private record Buyer(LegalEntity entity, AddressEndpoint endpoint) {}

    private Market market(String nominal, String listed) {
        LegalEntity seller = entity("SEL", KycStatus.APPROVED);
        Asset asset = asset();
        AssetHolder holder = holder(asset, seller, nominal);
        var listing = trading.createListing(seller.getId(), ACTOR, new CreateTradeListingRequest(
                holder.getId(), new BigDecimal(listed), new BigDecimal("10"), false, List.of(PaymentOption.OFFCHAIN_SEPA), false, "EUR", null, null));
        return new Market(asset, seller, holder, listings.findById(listing.id()).orElseThrow());
    }

    private Buyer buyer(KycStatus kyc) {
        LegalEntity e = entity("BUY", kyc);
        return new Buyer(e, endpoint(e));
    }

    private TradeExecutionResponse buy(Buyer buyer, Market m, String quantity) {
        return trading.buy(buyer.entity().getId(), ACTOR, m.listing().getId(), new BuyTradingOfferRequest(
                new BigDecimal(quantity), OrderType.MARKET, null, PaymentOption.OFFCHAIN_SEPA,
                WalletPreferenceMode.ENDPOINT, buyer.endpoint().getId(), null));
    }

    private TradeExecutionResponse declared(Buyer buyer, Market m, String quantity) {
        TradeExecutionResponse r = buy(buyer, m, quantity);
        return trading.settlePendingTrade(buyer.entity().getId(), ACTOR, r.id(), "SEPA-" + UUID.randomUUID());
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
        a.setName("Trading IT");
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
        h.setAcquisitionDate(java.time.LocalDate.now());
        return holders.saveAndFlush(h);
    }

    private AddressEndpoint endpoint(LegalEntity owner) {
        AddressEndpoint e = new AddressEndpoint();
        e.setOwnerType(AddressEndpoint.OwnerType.ENTITY);
        e.setOwnerId(owner.getId());
        e.setAddress("0x" + UUID.randomUUID().toString().replace("-", "") + "11111111");
        e.setAddressType(AddressEndpoint.AddressType.WALLET);
        e.setName("Buyer wallet");
        return endpoints.saveAndFlush(e);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private TradeExecution execution(UUID id) {
        return executions.findById(id).orElseThrow();
    }

    // ── 5A-03 / 5A-06: gates before reservation ─────────────────────────────

    @Test
    @DisplayName("an ineligible buyer (KYC not approved) is refused at buy and nothing is reserved")
    void ineligibleBuyerReservesNothing() {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.NOT_STARTED);

        assertThatThrownBy(() -> buy(buyer, m, "4")).isInstanceOf(ComplianceGateException.class);

        assertThat(listings.findById(m.listing().getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("10");
        assertThat(executions.findByBuyerEntityIdOrSellerEntityIdOrderByCreatedAtDesc(buyer.entity().getId(), buyer.entity().getId())).isEmpty();
    }

    @Test
    @DisplayName("a buyer with an expired KYC date is refused as well (shared party gate)")
    void expiredKycBuyerRefused() {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        LegalEntity e = entities.findById(buyer.entity().getId()).orElseThrow();
        e.setKycExpiryDate(java.time.LocalDate.now().minusDays(1));
        entities.saveAndFlush(e);

        assertThatThrownBy(() -> buy(buyer, m, "1")).isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("expired KYC");
    }

    @Test
    @DisplayName("reservation caps: 3 open reservations per buyer, one per listing, cool-down after a buyer cancel")
    void reservationCapsAndCooldown() {
        Buyer buyer = buyer(KycStatus.APPROVED);
        Market a = market("100", "10");
        Market b = market("100", "10");
        Market c = market("100", "10");
        Market d = market("100", "10");

        TradeExecutionResponse first = buy(buyer, a, "1");
        assertThatThrownBy(() -> buy(buyer, a, "1")).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("already hold an open reservation");
        buy(buyer, b, "1");
        buy(buyer, c, "1");
        assertThatThrownBy(() -> buy(buyer, d, "1")).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("at most 3");

        // the buyer cancels one: the slot is free again, but the same listing is cooling down
        trading.cancelPendingTrade(buyer.entity().getId(), ACTOR, first.id(), "changed my mind");
        assertThatThrownBy(() -> buy(buyer, a, "1")).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("wait");
        assertThat(buy(buyer, d, "1").settlementStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    // ── 5A-03: PAYMENT_UNRESOLVED ───────────────────────────────────────────

    @Test
    @DisplayName("seller dispute parks the trade: units stay reserved, listing not re-offered; operator RELEASE restores them")
    void disputeThenOperatorRelease() {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        TradeExecutionResponse declared = declared(buyer, m, "4");

        trading.disputePayment(m.seller().getId(), ACTOR, declared.id(), "no credit on our account");

        TradeExecution e = execution(declared.id());
        assertThat(e.getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED); // passes the V26 check constraint
        assertThat(e.getDisputeReason()).isEqualTo("no credit on our account");
        assertThat(listings.findById(m.listing().getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("6");
        assertThat(trading.listSellableHoldings(m.seller().getId()).stream()
                .filter(h -> h.holderId().equals(m.sellerHolder().getId())).findFirst().orElseThrow().availableQuantity())
                .isEqualByComparingTo("90"); // 100 - 6 still listed - 4 reserved
        assertThat(queue.listUnresolved()).anyMatch(u -> u.trade().id().equals(declared.id()));

        // both parties may add evidence notes
        queue.addNote(m.seller().getId(), ACTOR, "TRADER", declared.id(), "bank statement shows no credit");
        queue.addNote(buyer.entity().getId(), ACTOR, "TRADER", declared.id(), "payment slip attached");
        assertThat(queue.listNotes(buyer.entity().getId(), declared.id())).hasSize(2);

        trading.releaseUnresolved(ACTOR, declared.id(), "seller bank statement", "verified", UUID.randomUUID());
        assertThat(execution(declared.id()).getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(listings.findById(m.listing().getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("10");
        assertThat(queue.listUnresolved()).noneMatch(u -> u.trade().id().equals(declared.id()));
    }

    @Test
    @DisplayName("operator FORCE_SETTLE re-runs the gates and moves the register")
    void operatorForceSettle() {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        TradeExecutionResponse declared = declared(buyer, m, "4");
        trading.disputePayment(m.seller().getId(), ACTOR, declared.id(), "disputed");

        trading.forceSettleUnresolved(ACTOR, declared.id(), "payment evidenced by buyer bank", null, UUID.randomUUID());

        assertThat(execution(declared.id()).getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(holders.findById(m.sellerHolder().getId()).orElseThrow().getNominalAmount()).isEqualByComparingTo("96");
        assertThat(holders.sumActiveNominalByInvestorIdAndAssetId(buyer.entity().getId(), m.asset().getId()))
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a gate failing at confirm time hands the paid trade to the operator; the register is untouched")
    void gateFailureAtConfirmBecomesUnresolved() {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        TradeExecutionResponse declared = declared(buyer, m, "4");
        LegalEntity e = entities.findById(buyer.entity().getId()).orElseThrow();
        e.setKycStatus(KycStatus.EXPIRED);
        entities.saveAndFlush(e);

        TradeExecutionResponse result = trading.confirmPaymentReceived(m.seller().getId(), ACTOR, declared.id());

        assertThat(result.settlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertThat(execution(declared.id()).getUnresolvedReason()).contains("GATE_FAILED_AT_CONFIRM");
        assertThat(holders.findById(m.sellerHolder().getId()).orElseThrow().getNominalAmount()).isEqualByComparingTo("100");
    }

    // ── 5A-03: the timeout job ──────────────────────────────────────────────

    @Test
    @DisplayName("timeout job: overdue PENDING -> FAILED (quantity back); overdue declared payment -> PAYMENT_UNRESOLVED (reservation kept)")
    void timeoutJobEndToEnd() {
        Market m = market("100", "10");
        Buyer pendingBuyer = buyer(KycStatus.APPROVED);
        Buyer payingBuyer = buyer(KycStatus.APPROVED);
        TradeExecutionResponse pending = buy(pendingBuyer, m, "2");
        TradeExecutionResponse paid = declared(payingBuyer, m, "3");
        jdbc.update("UPDATE trade_execution SET created_at = now() - interval '100 hours' WHERE id = ?", pending.id());
        jdbc.update("UPDATE trade_execution SET payment_declared_at = now() - interval '100 hours' WHERE id = ?", paid.id());

        timeoutJob.timeoutStuckTrades();

        assertThat(execution(pending.id()).getSettlementStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(execution(paid.id()).getSettlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        // 10 - 2 - 3 = 5 open; the failed 2 are back (7), the unresolved 3 stay reserved
        assertThat(listings.findById(m.listing().getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("RACE: the job cannot overwrite a SETTLED trade - the seller's confirm holds the row lock, the job then sees SETTLED")
    void timeoutJobDoesNotOverwriteConcurrentConfirm() throws Exception {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        TradeExecutionResponse declared = declared(buyer, m, "4");
        jdbc.update("UPDATE trade_execution SET payment_declared_at = now() - interval '100 hours' WHERE id = ?", declared.id());
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);

        CountDownLatch confirmHoldsLock = new CountDownLatch(1);
        CountDownLatch jobStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirm = pool.submit(() -> tx.executeWithoutResult(status -> {
                executions.findByIdForUpdate(declared.id()).orElseThrow();   // seller's tx owns the row
                confirmHoldsLock.countDown();
                try {
                    assertThat(jobStarted.await(10, TimeUnit.SECONDS)).isTrue();
                    Thread.sleep(500);                                       // let the job block on the lock
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                trading.confirmPaymentReceived(m.seller().getId(), ACTOR, declared.id());
            }));
            assertThat(confirmHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> job = pool.submit(() -> {
                jobStarted.countDown();
                return timeoutProcessor.timeoutAwaiting(declared.id(), cutoff);
            });
            confirm.get(30, TimeUnit.SECONDS);
            assertThat(job.get(30, TimeUnit.SECONDS)).isFalse();             // job saw SETTLED after the lock was released
        } finally {
            pool.shutdownNow();
        }

        assertThat(execution(declared.id()).getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(holders.findById(m.sellerHolder().getId()).orElseThrow().getNominalAmount()).isEqualByComparingTo("96");
        // and the quantity was NOT handed back to the listing
        assertThat(listings.findById(m.listing().getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("belt and braces: a stale copy of the execution cannot be saved over a SETTLED row (@Version)")
    void staleWriteIsRejectedByVersion() {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        TradeExecutionResponse declared = declared(buyer, m, "4");
        TradeExecution stale = execution(declared.id());                    // detached copy, AWAITING, version n
        trading.confirmPaymentReceived(m.seller().getId(), ACTOR, declared.id());

        stale.setSettlementStatus(SettlementStatus.FAILED);
        assertThatThrownBy(() -> executions.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(execution(declared.id()).getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
    }

    // ── 5A-04 / 5A-05 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("removeHolder cancels the seller's listings, cancels unpaid trades and parks paid ones; nothing is ever settled against the removed row")
    void removeHolderInvalidatesListingsAndTrades() throws Exception {
        Market m = market("100", "10");
        Buyer paidBuyer = buyer(KycStatus.APPROVED);
        Buyer unpaidBuyer = buyer(KycStatus.APPROVED);
        TradeExecutionResponse paid = declared(paidBuyer, m, "4");
        TradeExecutionResponse unpaid = buy(unpaidBuyer, m, "2");

        holderService.removeHolder(m.asset().getId(), m.sellerHolder().getId(), ACTOR, "REGISTRY_ADMIN");

        await(() -> listings.findById(m.listing().getId()).orElseThrow().getStatus() == ListingStatus.CANCELLED
                && execution(unpaid.id()).getSettlementStatus() == SettlementStatus.CANCELLED
                && execution(paid.id()).getSettlementStatus() == SettlementStatus.PAYMENT_UNRESOLVED,
                "listener to invalidate listing and trades");
        assertThat(execution(paid.id()).getUnresolvedReason()).contains("REGISTER_ENTRY_REMOVED");

        // the paid trade can no longer be settled against the removed entry - not by the seller, not by the operator
        assertThatThrownBy(() -> trading.forceSettleUnresolved(ACTOR, paid.id(), "basis", null, UUID.randomUUID()))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("removed");
        assertThat(holders.findById(m.sellerHolder().getId()).orElseThrow().getNominalAmount()).isEqualByComparingTo("100");
        assertThat(holders.sumActiveNominalByInvestorIdAndAssetId(paidBuyer.entity().getId(), m.asset().getId()))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("supply inflation is closed even before the listener runs: confirm against a removed entry does not debit / credit")
    void confirmAgainstRemovedEntryDoesNotInflateSupply() {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        TradeExecutionResponse declared = declared(buyer, m, "4");
        AssetHolder row = holders.findById(m.sellerHolder().getId()).orElseThrow();
        row.setRemovedAt(Instant.now());
        holders.saveAndFlush(row);

        TradeExecutionResponse result = trading.confirmPaymentReceived(m.seller().getId(), ACTOR, declared.id());

        assertThat(result.settlementStatus()).isEqualTo(SettlementStatus.PAYMENT_UNRESOLVED);
        assertThat(holders.findById(row.getId()).orElseThrow().getNominalAmount()).isEqualByComparingTo("100");
        assertThat(holders.sumActiveNominalByInvestorIdAndAssetId(buyer.entity().getId(), m.asset().getId()))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("SUSPENDED / REDEEMED assets block buy and listing creation; the suspend event cancels the listings")
    void suspendedAndRedeemedBlockTrading() throws Exception {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        Asset asset = assets.findById(m.asset().getId()).orElseThrow();

        asset.setStatus(AssetStatus.SUSPENDED);
        assets.saveAndFlush(asset);
        assertThatThrownBy(() -> buy(buyer, m, "1")).isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("SUSPENDED");
        assertThatThrownBy(() -> trading.createListing(m.seller().getId(), ACTOR, new CreateTradeListingRequest(
                m.sellerHolder().getId(), BigDecimal.ONE, BigDecimal.TEN, false, List.of(PaymentOption.OFFCHAIN_SEPA))))
                .isInstanceOf(InvalidStateTransitionException.class);

        tx.executeWithoutResult(s -> publisher.publishEvent(new AssetSuspendedEvent(asset.getId(), ACTOR, "REGISTRY_ADMIN")));
        await(() -> listings.findById(m.listing().getId()).orElseThrow().getStatus() == ListingStatus.CANCELLED,
                "suspend listener to cancel the listing");

        // a second market whose asset is REDEEMED (the first listing is already cancelled by the event)
        Market redeemed = market("100", "10");
        Asset current = assets.findById(redeemed.asset().getId()).orElseThrow();
        current.setStatus(AssetStatus.REDEEMED);
        assets.saveAndFlush(current);
        assertThatThrownBy(() -> buy(buyer, redeemed, "1")).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("REDEEMED");
    }

    // ── 5A-01 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the buyer's company flag (still true in legacy rows) never settles instantly; the default buy stays PENDING")
    void buyerFlagIsIgnored() {
        Market m = market("100", "10");
        Buyer buyer = buyer(KycStatus.APPROVED);
        jdbc.update("INSERT INTO company_trader_settings (legal_entity_id, immediate_settlement_enabled) VALUES (?, true)",
                buyer.entity().getId());

        TradeExecutionResponse r = buy(buyer, m, "4");

        assertThat(r.settlementStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(r.instantSettlement()).isFalse();
        assertThat(holders.findById(m.sellerHolder().getId()).orElseThrow().getNominalAmount()).isEqualByComparingTo("100");
    }
}
