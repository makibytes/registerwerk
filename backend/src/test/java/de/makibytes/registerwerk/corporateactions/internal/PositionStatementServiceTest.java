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
}
