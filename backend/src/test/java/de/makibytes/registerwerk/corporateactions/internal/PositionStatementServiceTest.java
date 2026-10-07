package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.shared.DocumentSigningService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PositionStatementService PDF generation")
class PositionStatementServiceTest {

    @Mock private AssetHolderRepository holderRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private LegalEntityRepository entityRepository;
    @Mock private DocumentSigningService signingService;
    @org.mockito.Spy private de.makibytes.registerwerk.shared.RegisterClock registerClock =
            new de.makibytes.registerwerk.shared.RegisterClock(java.time.Clock.systemDefaultZone(), java.time.ZoneId.systemDefault());

    @InjectMocks
    private PositionStatementService service;

    @Test
    @DisplayName("a statement with more holdings than fit one page paginates instead of truncating")
    void paginatesLargeStatements() throws Exception {
        UUID entityId = UUID.randomUUID();
        LegalEntity entity = new LegalEntity();
        entity.setId(entityId);
        entity.setEntityNumber("DEMO-PAGE-001");
        entity.setRegistrationCountry("DE");

        List<AssetHolder> holdings = new ArrayList<>();
        List<Asset> assets = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            Asset asset = new Asset();
            asset.setId(UUID.randomUUID());
            asset.setName("Bond Series " + i);
            asset.setIsin(String.format("DE000PAGE%03d", i));
            assets.add(asset);

            AssetHolder holder = new AssetHolder();
            holder.setAssetId(asset.getId());
            holder.setWalletAddress("0x" + "%040x".formatted(i));
            holder.setNominalAmount(BigDecimal.valueOf(1000L + i));
            holdings.add(holder);
        }

        when(entityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        when(holderRepository.findActiveByInvestorId(entityId)).thenReturn(holdings);
        when(assetRepository.findAllById(anyIterable())).thenReturn(assets);

        byte[] pdf = service.generateForEntity(entityId);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            // The last holding must be present — the old renderer silently dropped
            // everything past the first page.
            assertThat(text).contains("DE000PAGE079");
            assertThat(text).contains("DE000PAGE000");
        }
    }

    // ── 9A-05: Depotauszug of an unreconciled register / handed-over positions ──

    private static Asset asset(String name, String isin, de.makibytes.registerwerk.asset.api.AssetStatus status) {
        Asset a = new Asset();
        a.setId(UUID.randomUUID());
        a.setName(name);
        a.setIsin(isin);
        a.setStatus(status);
        return a;
    }

    private static AssetHolder holding(Asset asset, String nominal, int walletSeed) {
        AssetHolder h = new AssetHolder();
        h.setAssetId(asset.getId());
        h.setWalletAddress("0x" + "%040x".formatted(walletSeed));
        h.setNominalAmount(new BigDecimal(nominal));
        return h;
    }

    @Test
    @DisplayName("a BLOCKED asset's nominal is shown as unconfirmed; transferred-out positions are not listed as current")
    void blockedAssetRowIsUnconfirmedAndTransferredOutIsNotCurrent() throws Exception {
        UUID entityId = UUID.randomUUID();
        LegalEntity entity = new LegalEntity();
        entity.setId(entityId);
        entity.setEntityNumber("DEMO-9A05");
        entity.setRegistrationCountry("DE");

        Asset ok = asset("Reconciled Bond", "DE000OKBOND01", de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED);
        ok.setLastSuccessfulHolderSyncAt(java.time.Instant.parse("2026-09-01T10:15:00Z"));
        Asset blocked = asset("Blocked Bond", "DE000BLKBND02", de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED);
        blocked.setHolderSyncStatus(de.makibytes.registerwerk.asset.api.HolderSyncStatus.BLOCKED);
        blocked.setLastSuccessfulHolderSyncAt(java.time.Instant.parse("2026-08-01T08:00:00Z"));
        Asset out = asset("Handed Over Bond", "DE000OUTBND03", de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFERRED_OUT);
        Asset redeeming = asset("Redeeming Bond", "DE000REDBND04", de.makibytes.registerwerk.asset.api.AssetStatus.REDEMPTION_PENDING);

        when(entityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        when(holderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of(
                holding(ok, "1111", 1), holding(blocked, "7777", 2), holding(out, "5555", 3), holding(redeeming, "3333", 4)));
        when(assetRepository.findAllById(anyIterable())).thenReturn(List.of(ok, blocked, out, redeeming));

        byte[] pdf = service.generateForEntity(entityId);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("1111");                        // reconciled row keeps its nominal
            assertThat(text).doesNotContain("7777");                  // BLOCKED: never print a possibly stale nominal
            assertThat(text).contains("unconfirmed");
            assertThat(text).doesNotContain("5555");                  // handed over: not a current position
            assertThat(text).contains("DE000OUTBND03").contains("No longer administered here");
            assertThat(text).contains("REDEMPTION_PENDING");
            assertThat(text).contains("3333");
            assertThat(text).contains("oldest on-chain reconciliation 2026-08-01 08:00 UTC");
        }
    }
}
