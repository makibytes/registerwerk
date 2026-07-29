package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.registertransfer.api.InspectionLegalBasis;
import de.makibytes.registerwerk.shared.DocumentSigningService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the §10 eWpG disclosure boundary rendered into the extract PDF: Berechtigte
 * (ISSUER/HOLDER/BENEFICIARY) see disposal restrictions, a LEGITIMATE_INTEREST
 * applicant sees only the security data and aggregate holdings. A test that only
 * checks "PDF bytes exist" would miss a regression that leaked restricted fields
 * to an unentitled applicant, so this asserts on the actual extracted text.
 */
class RegisterExtractRendererTest {

    private final DocumentSigningService signingService = mock(DocumentSigningService.class);
    private final RegisterExtractRenderer renderer = new RegisterExtractRenderer(signingService);

    private static Asset asset(String name, String isin) {
        Asset asset = new Asset();
        asset.setName(name);
        asset.setIsin(isin);
        asset.setEntryType(EntryType.INDIVIDUAL);
        return asset;
    }

    private static AssetHolder holder(String reference, String nominal, String restrictions) {
        AssetHolder h = new AssetHolder();
        h.setHolderReference(reference);
        h.setNominalAmount(new BigDecimal(nominal));
        h.setDisposalRestrictions(restrictions);
        return h;
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void render_producesAValidPdfWithSecurityData() throws IOException {
        byte[] pdf = renderer.render(asset("Test Bond", "DE000TESTBND1"), List.of(),
                InspectionLegalBasis.HOLDER, "Jane Doe");

        assertThat(pdf).isNotEmpty();
        String text = extractText(pdf);
        assertThat(text).contains("Test Bond").contains("DE000TESTBND1").contains("Jane Doe");
    }

    @Test
    void render_berechtigter_seesDisposalRestrictions() throws IOException {
        AssetHolder h = holder("HREF-1", "1000", "Pledged to Bank X");

        byte[] pdf = renderer.render(asset("Test Bond", "DE0001"), List.of(h),
                InspectionLegalBasis.HOLDER, "Jane Doe");

        String text = extractText(pdf);
        assertThat(text).contains("HREF-1").contains("Pledged to Bank X").contains("Restrictions");
    }

    @Test
    void render_legitimateInterestApplicant_doesNotSeeDisposalRestrictions() throws IOException {
        AssetHolder h = holder("HREF-1", "1000", "Pledged to Bank X");

        byte[] pdf = renderer.render(asset("Test Bond", "DE0001"), List.of(h),
                InspectionLegalBasis.LEGITIMATE_INTEREST, "Curious Journalist");

        String text = extractText(pdf);
        assertThat(text).contains("HREF-1"); // reference and nominal are always shown
        assertThat(text).doesNotContain("Pledged to Bank X");
        assertThat(text).doesNotContain("Restrictions");
    }

    @Test
    void render_truncatesLongRestrictionsText() throws IOException {
        String longRestriction = "A".repeat(50);
        AssetHolder h = holder("HREF-1", "1000", longRestriction);

        byte[] pdf = renderer.render(asset("Test Bond", "DE0001"), List.of(h),
                InspectionLegalBasis.ISSUER, "Requester");

        String text = extractText(pdf);
        assertThat(text).doesNotContain(longRestriction);
        assertThat(text).contains("A".repeat(29) + "…");
    }

    @Test
    void render_collectiveHolding_showsPlaceholderWhenNoHolderReference() throws IOException {
        AssetHolder h = new AssetHolder();
        h.setNominalAmount(BigDecimal.TEN);
        // holderReference left null -> collective entry (Sammeleintragung).

        byte[] pdf = renderer.render(asset("Test Bond", "DE0001"), List.of(h),
                InspectionLegalBasis.HOLDER, "Requester");

        assertThat(extractText(pdf)).contains("Sammeleintragung");
    }

    @Test
    void render_handlesNullAssetNameAndRequesterNameGracefully() throws IOException {
        byte[] pdf = renderer.render(asset(null, null), List.of(),
                InspectionLegalBasis.LEGITIMATE_INTEREST, null);

        assertThat(pdf).isNotEmpty();
        assertThat(extractText(pdf)).contains("—");
    }

    @Test
    void render_manyHolders_paginatesInsteadOfTruncating() throws IOException {
        List<AssetHolder> holders = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            holders.add(holder("HREF-" + i, "1", null));
        }

        byte[] pdf = renderer.render(asset("Test Bond", "DE0001"), holders,
                InspectionLegalBasis.HOLDER, "Requester");

        String text = extractText(pdf);
        // §10 must disclose every holder of record (finding #12) — overflow now continues on
        // additional pages instead of silently truncating past the first page's capacity.
        assertThat(text).doesNotContain("gekürzt");
        assertThat(text).contains("HREF-0").contains("HREF-79");
        assertThat(text).contains("Fortsetzung");
        try (org.apache.pdfbox.pdmodel.PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
        }
    }

    @Test
    void render_isSignedWhenSigningIsConfigured() throws IOException {
        when(signingService.isConfigured()).thenReturn(true);
        when(signingService.signPdf(any(), anyString())).thenReturn("signed-bytes".getBytes());

        byte[] pdf = renderer.render(asset("Test Bond", "DE0001"), List.of(),
                InspectionLegalBasis.HOLDER, "Jane Doe");

        assertThat(pdf).isEqualTo("signed-bytes".getBytes());
        verify(signingService).signPdf(any(), anyString());
    }
}
