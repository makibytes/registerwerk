package de.makibytes.registerwerk.audit.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.audit.api.ChainVerificationView;
import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import de.makibytes.registerwerk.shared.CsvWriter;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.api.PageResponse;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
@Validated
public class AuditController {

    /** Hard cap on a single export, independent of what the caller requests — audit_event is a
     *  partitioned, potentially very large table, and this is meant for a bounded date range or
     *  case, not an unbounded dump. */
    private static final int MAX_EXPORT_ROWS = 50_000;

    private final AuditApi auditApi;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final Optional<SigningKeyProvider> signingKeyProvider;

    public AuditController(AuditApi auditApi, tools.jackson.databind.ObjectMapper objectMapper,
                            Optional<SigningKeyProvider> signingKeyProvider) {
        this.auditApi = auditApi;
        this.objectMapper = objectMapper;
        this.signingKeyProvider = signingKeyProvider;
    }

    @GetMapping("/events")
    public ResponseEntity<PageResponse<AuditEventView>> searchEvents(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {

        if (subjectType != null || subjectId != null || eventType != null || actorId != null
                || from != null || to != null) {
            return ResponseEntity.ok(PageResponse.of(
                    auditApi.findFiltered(subjectType, subjectId, eventType, actorId, from, to, pageable)));
        }
        return ResponseEntity.ok(PageResponse.of(auditApi.findAll(pageable)));
    }

    @GetMapping("/reports/kyc-overrides")
    public ResponseEntity<PageResponse<AuditEventView>> kycOverrideReport(
            @RequestParam(required = false) String jurisdiction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        return ResponseEntity.ok(PageResponse.of(auditApi.findKycOverrideApprovals(jurisdiction, from, to, pageable)));
    }

    /**
     * CSV/evidence export for a date range or case — previously the only exports anywhere in
     * the portal were per-customer PDFs, so compliance couldn't hand an auditor a data extract
     * without direct database access, and the audit hash-chain's tamper-evidence work never
     * surfaced anywhere that pays off. Not signed — see {@code /events/export/signed} for the
     * Ed25519-signed extract.
     */
    @GetMapping(value = "/events/export", produces = "text/csv")
    public ResponseEntity<String> exportEvents(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "" + MAX_EXPORT_ROWS) @Min(1) @Max(MAX_EXPORT_ROWS) int limit) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by("occurredAt").ascending());
        List<AuditEventView> events = auditApi.findForExport(
                subjectType, subjectId, eventType, actorId, from, to, pageable);
        String csv = toCsv(events);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"audit-export.csv\"")
                .body(csv);
    }

    private String toCsv(List<AuditEventView> events) {
        List<String> header = List.of(
                "id", "eventType", "subjectType", "subjectId", "actorId", "actorRole", "occurredAt", "payload");
        List<List<Object>> rows = events.stream().map(e -> List.<Object>of(
                e.id(), e.eventType(), nullToEmpty(e.subjectType()), nullToEmpty(e.subjectId()),
                nullToEmpty(e.actorId()), nullToEmpty(e.actorRole()), e.occurredAt(),
                serializePayload(e.payload())
        )).toList();
        return CsvWriter.write(header, rows);
    }

    /**
     * Signed evidence export — same bounded date-range/case extract as {@link #exportEvents},
     * plus each row's hash-chain position ({@code sequenceNo}, {@code entryHash}) and Ed25519
     * signature ({@code entrySig}), and a signature over the exported bytes themselves
     * ({@code X-Export-Signature-Ed25519}, hex, computed over the SHA-256 digest in
     * {@code X-Export-Digest-Sha256}). The per-row signatures already let an auditor verify each
     * entry was genuinely produced by this deployment's signing key; the export-level signature
     * additionally proves the specific file they received matches exactly what was exported,
     * without having to trust the transport/storage in between. Verify against
     * {@link #signingKey()}. Falls back to an unsigned export (with {@code X-Export-Signed:
     * false}) when no {@link SigningKeyProvider} is configured — the extract and its per-row
     * hashes are still useful without a signing key, just not independently verifiable.
     */
    @GetMapping(value = "/events/export/signed", produces = "text/csv")
    public ResponseEntity<String> exportEventsSigned(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "" + MAX_EXPORT_ROWS) @Min(1) @Max(MAX_EXPORT_ROWS) int limit) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by("occurredAt").ascending());
        List<AuditEventView> events = auditApi.findForExport(
                subjectType, subjectId, eventType, actorId, from, to, pageable);
        String csv = toSignedCsv(events);
        byte[] digest = sha256(csv.getBytes(StandardCharsets.UTF_8));
        String digestHex = HexFormat.of().formatHex(digest);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"audit-export-signed.csv\"")
                .header("X-Export-Digest-Sha256", digestHex);

        return signingKeyProvider
                .map(provider -> response
                        .header("X-Export-Signed", "true")
                        .header("X-Export-Signature-Ed25519", HexFormat.of().formatHex(provider.sign(digest)))
                        .header("X-Audit-Signing-Key-Name", provider.name())
                        .body(csv))
                .orElseGet(() -> response
                        .header("X-Export-Signed", "false")
                        .body(csv));
    }

    /**
     * The Ed25519 public key an auditor needs to verify {@code /events/export/signed} and
     * individual rows' {@code entrySig}. Absent (200 with {@code configured: false}) rather than
     * 404 when no {@link SigningKeyProvider} is active, so a caller can distinguish "not
     * configured in this environment" from "wrong URL".
     */
    @GetMapping("/signing-key")
    public ResponseEntity<SigningKeyResponse> signingKey() {
        return ResponseEntity.ok(signingKeyProvider
                .map(p -> new SigningKeyResponse(true, p.name(), Base64.getEncoder().encodeToString(p.publicKey()), p.createdAt()))
                .orElseGet(() -> new SigningKeyResponse(false, null, null, null)));
    }

    public record SigningKeyResponse(boolean configured, String name, String publicKeyBase64, Instant createdAt) {}

    private String toSignedCsv(List<AuditEventView> events) {
        List<String> header = List.of(
                "id", "eventType", "subjectType", "subjectId", "actorId", "actorRole", "occurredAt", "payload",
                "sequenceNo", "entryHash", "entrySig");
        List<List<Object>> rows = events.stream().map(e -> List.<Object>of(
                e.id(), e.eventType(), nullToEmpty(e.subjectType()), nullToEmpty(e.subjectId()),
                nullToEmpty(e.actorId()), nullToEmpty(e.actorRole()), e.occurredAt(),
                serializePayload(e.payload()), nullToEmpty(e.sequenceNo()),
                nullToEmpty(e.entryHashHex()), nullToEmpty(e.entrySigHex())
        )).toList();
        return CsvWriter.write(header, rows);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Object nullToEmpty(Object v) { return v == null ? "" : v; }

    private String serializePayload(java.util.Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "";
        }
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<AuditEventView> getEvent(@PathVariable UUID id) {
        AuditEventView event = auditApi.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("AuditEvent", id));
        return ResponseEntity.ok(event);
    }

    /** Most recently computed hash-chain integrity result (nightly job or a prior on-demand run). */
    @GetMapping("/chain/status")
    public ResponseEntity<ChainVerificationView> chainStatus() {
        return ResponseEntity.ok(auditApi.chainVerificationStatus());
    }

    /**
     * Triggers a full hash-chain verification scan on demand, rather than only unattended at
     * 03:30 nightly with results visible solely via {@code /actuator/health} — gives operators
     * a way to confirm integrity right now.
     */
    @PostMapping("/chain/verify")
    public ResponseEntity<ChainVerificationView> verifyChain() {
        return ResponseEntity.ok(auditApi.verifyChainNow());
    }
}
