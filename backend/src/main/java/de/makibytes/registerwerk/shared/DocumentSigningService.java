package de.makibytes.registerwerk.shared;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Applies a real digital signature (CMS/PKCS#7 detached, embedded per ISO 32000-2 — PAdES-B-B
 * baseline profile) to registry-generated PDFs, via PDFBox's external-signing API and
 * BouncyCastle's CMS signed-data generator.
 *
 * <p>Several documents (Registerauszug, Depotauszug, Steuerbescheinigung, Registereinsicht)
 * render a "PAdES-B-LT Signatur vorhanden" footer only when actually signed — this service
 * applies the genuine signature when a signing keystore is configured
 * ({@code registerwerk.docsig.keystore-path} etc.); {@link #isConfigured()} tells callers
 * whether to render an honest "signed" or "unsigned" claim — never assume one.
 *
 * <p>This is deliberately the <b>-B-B</b> (Basic) PAdES profile, not <b>-B-LT</b> (Long-Term,
 * requiring embedded OCSP/CRL revocation info and a trusted timestamp authority) — that is a
 * materially larger integration (a TSA endpoint + CRL/OCSP fetching) intentionally out of scope
 * here. Do not re-introduce a "-LT" claim without actually implementing it.
 */
@Service
public class DocumentSigningService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSigningService.class);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final String keystorePath;
    private final String keystorePassword;
    private final String keyAlias;
    private final String keyPassword;

    private PrivateKey signingKey;
    private X509Certificate signingCertificate;
    private List<X509Certificate> certificateChain;
    private boolean configured;

    public DocumentSigningService(
            @Value("${registerwerk.docsig.keystore-path:}") String keystorePath,
            @Value("${registerwerk.docsig.keystore-password:}") String keystorePassword,
            @Value("${registerwerk.docsig.key-alias:}") String keyAlias,
            @Value("${registerwerk.docsig.key-password:}") String keyPassword) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keyAlias = keyAlias;
        this.keyPassword = keyPassword;
    }

    @PostConstruct
    void init() {
        if (keystorePath == null || keystorePath.isBlank()) {
            log.info("registerwerk.docsig.keystore-path not set — registry-generated PDFs "
                    + "(Registerauszug/Depotauszug/Steuerbescheinigung/Registereinsicht) will be "
                    + "rendered UNSIGNED. This is expected in dev/demo; configure a signing "
                    + "keystore before relying on these documents' authenticity in production.");
            return;
        }
        try (InputStream in = new FileInputStream(keystorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, keystorePassword.toCharArray());
            char[] pw = (keyPassword == null || keyPassword.isBlank())
                    ? keystorePassword.toCharArray() : keyPassword.toCharArray();
            this.signingKey = (PrivateKey) keyStore.getKey(keyAlias, pw);
            if (this.signingKey == null) {
                throw new IllegalStateException("No private key found for alias '" + keyAlias + "'");
            }
            Certificate[] chain = keyStore.getCertificateChain(keyAlias);
            if (chain == null || chain.length == 0) {
                throw new IllegalStateException("No certificate chain found for alias '" + keyAlias + "'");
            }
            this.signingCertificate = (X509Certificate) chain[0];
            List<X509Certificate> certs = new ArrayList<>();
            for (Certificate c : chain) {
                certs.add((X509Certificate) c);
            }
            this.certificateChain = certs;
            this.configured = true;
            log.info("Document signing configured: alias={}, subject={}", keyAlias, signingCertificate.getSubjectX500Principal());
        } catch (Exception e) {
            log.error("Failed to load document-signing keystore from {} — PDFs will be rendered UNSIGNED", keystorePath, e);
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    /** Human-readable description of the active signing certificate, for the honest footer text. */
    public String signerDescription() {
        return configured ? signingCertificate.getSubjectX500Principal().getName() : "";
    }

    /**
     * Embeds a detached CMS/PKCS#7 signature into the given PDF. Throws if {@link #isConfigured()}
     * is false — callers must check that first and render an "unsigned" footer instead of calling
     * this.
     */
    public byte[] signPdf(byte[] unsignedPdf, String reason) {
        if (!configured) {
            throw new IllegalStateException("Document signing is not configured "
                    + "(registerwerk.docsig.keystore-path is unset)");
        }
        try (PDDocument doc = Loader.loadPDF(unsignedPdf)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(signerDescription());
            signature.setReason(reason);
            signature.setSignDate(Calendar.getInstance());

            doc.addSignature(signature, content -> cmsSign(content.readAllBytes()));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF signing failed", e);
        }
    }

    private byte[] cmsSign(byte[] content) throws IOException {
        try {
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC")
                    .build(signingKey);
            generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                    .build(contentSigner, signingCertificate));
            generator.addCertificates(new JcaCertStore(certificateChain));
            CMSSignedData signedData = generator.generate(new CMSProcessableByteArray(content), false);
            return signedData.getEncoded();
        } catch (Exception e) {
            throw new IOException("CMS signing failed", e);
        }
    }

    /** Timestamp used in signed-document metadata, kept here so tests can verify wiring. */
    public Instant now() {
        return Instant.now();
    }
}
