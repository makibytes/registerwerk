package de.makibytes.registerwerk.audit.web;

import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditController signed-export unit tests (Track 7-2)")
class AuditControllerTest {

    @Mock private AuditApi auditApi;
    @Mock private SigningKeyProvider signingKeyProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuditEventView sampleEvent() {
        return new AuditEventView(UUID.randomUUID(), "ASSET_APPROVED", "Asset", UUID.randomUUID(),
                UUID.randomUUID(), "REGISTRY_ADMIN", Map.of("k", "v"), Instant.parse("2026-08-01T10:00:00Z"),
                42L, "deadbeef", "cafebabe");
    }

    @Test
    @DisplayName("exportEventsSigned includes hash-chain columns and a signature when a signing key is configured")
    void exportEventsSigned_configured_includesSignature() {
        AuditController controller = new AuditController(auditApi, objectMapper, Optional.of(signingKeyProvider));
        when(auditApi.findForExport(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(sampleEvent()));
        when(signingKeyProvider.sign(any())).thenReturn(new byte[]{1, 2, 3});
        when(signingKeyProvider.name()).thenReturn("gcp-kms-ed25519");

        ResponseEntity<String> response = controller.exportEventsSigned(null, null, null, null, null, null, 50_000);

        assertThat(response.getBody()).contains("sequenceNo", "entryHash", "entrySig", "42", "deadbeef", "cafebabe");
        assertThat(response.getHeaders().getFirst("X-Export-Signed")).isEqualTo("true");
        assertThat(response.getHeaders().getFirst("X-Export-Signature-Ed25519")).isEqualTo("010203");
        assertThat(response.getHeaders().getFirst("X-Audit-Signing-Key-Name")).isEqualTo("gcp-kms-ed25519");
        assertThat(response.getHeaders().getFirst("X-Export-Digest-Sha256")).isNotBlank();
    }

    @Test
    @DisplayName("the export digest header is the real SHA-256 of the exported CSV bytes")
    void exportEventsSigned_digestMatchesCsvBytes() throws Exception {
        AuditController controller = new AuditController(auditApi, objectMapper, Optional.of(signingKeyProvider));
        when(auditApi.findForExport(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(sampleEvent()));
        when(signingKeyProvider.sign(any())).thenReturn(new byte[]{9});

        ResponseEntity<String> response = controller.exportEventsSigned(null, null, null, null, null, null, 50_000);

        byte[] expectedDigest = MessageDigest.getInstance("SHA-256")
                .digest(response.getBody().getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeaders().getFirst("X-Export-Digest-Sha256"))
                .isEqualTo(HexFormat.of().formatHex(expectedDigest));
    }

    @Test
    @DisplayName("exportEventsSigned falls back to an unsigned export when no signing key is configured")
    void exportEventsSigned_notConfigured_fallsBackUnsigned() {
        AuditController controller = new AuditController(auditApi, objectMapper, Optional.empty());
        when(auditApi.findForExport(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(sampleEvent()));

        ResponseEntity<String> response = controller.exportEventsSigned(null, null, null, null, null, null, 50_000);

        assertThat(response.getHeaders().getFirst("X-Export-Signed")).isEqualTo("false");
        assertThat(response.getHeaders().getFirst("X-Export-Signature-Ed25519")).isNull();
        assertThat(response.getBody()).contains("sequenceNo");
    }

    @Test
    @DisplayName("signingKey reports the public key and metadata when configured")
    void signingKey_configured_returnsPublicKey() {
        AuditController controller = new AuditController(auditApi, objectMapper, Optional.of(signingKeyProvider));
        when(signingKeyProvider.name()).thenReturn("env-var-dev");
        when(signingKeyProvider.publicKey()).thenReturn(new byte[]{1, 2, 3, 4});
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        when(signingKeyProvider.createdAt()).thenReturn(createdAt);

        ResponseEntity<AuditController.SigningKeyResponse> response = controller.signingKey();

        AuditController.SigningKeyResponse body = response.getBody();
        assertThat(body.configured()).isTrue();
        assertThat(body.name()).isEqualTo("env-var-dev");
        assertThat(body.publicKeyBase64()).isEqualTo(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}));
        assertThat(body.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("signingKey reports not-configured rather than 404 when no signing key is active")
    void signingKey_notConfigured_reportsFalse() {
        AuditController controller = new AuditController(auditApi, objectMapper, Optional.empty());

        ResponseEntity<AuditController.SigningKeyResponse> response = controller.signingKey();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().configured()).isFalse();
        assertThat(response.getBody().publicKeyBase64()).isNull();
    }

    @Test
    @DisplayName("searchEvents forwards combined filters instead of silently ignoring date ranges")
    void searchEvents_combinedFilters_areApplied() {
        AuditController controller = new AuditController(auditApi, objectMapper, Optional.empty());
        UUID subjectId = UUID.randomUUID();
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-01T23:59:59Z");
        Pageable pageable = Pageable.ofSize(25);
        when(auditApi.findFiltered("ASSET", subjectId, "ASSET_APPROVED", null, from, to, pageable))
                .thenReturn(Page.empty(pageable));

        controller.searchEvents("ASSET", subjectId, "ASSET_APPROVED", null, from, to, pageable);

        verify(auditApi).findFiltered("ASSET", subjectId, "ASSET_APPROVED", null, from, to, pageable);
    }
}
