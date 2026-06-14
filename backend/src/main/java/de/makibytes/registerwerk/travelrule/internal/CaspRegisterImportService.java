package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.CaspAuthorizationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bulk import of CASP authorization entries from CSV — typically an export of
 * the ESMA MiCA register or an NCA list, transformed to the canonical columns:
 *
 * <pre>
 * legal_name;vasp_did;lei;home_member_state;status;authorization_id;valid_from;valid_until;notes
 * </pre>
 *
 * <ul>
 *   <li>Header matching is case-insensitive; both {@code ;} and {@code ,}
 *       delimiters are detected from the header line.</li>
 *   <li>{@code legal_name} and {@code status} are required; if {@code vasp_did}
 *       is empty but an LEI is present, the identifier {@code lei:<LEI>} is
 *       synthesized (the ESMA register carries no DIDs).</li>
 *   <li>Status values are mapped tolerantly — ESMA uses British spelling
 *       ("Authorised"); "Withdrawn" maps to {@code REVOKED}.</li>
 *   <li>Rows are upserted keyed by {@code vaspDid}; the import is all-rows
 *       best-effort: bad lines are collected as errors, good lines proceed.</li>
 * </ul>
 */
@Service
public class CaspRegisterImportService {

    private static final Logger log = LoggerFactory.getLogger(CaspRegisterImportService.class);
    private static final int MAX_REPORTED_ERRORS = 20;

    private final CaspRegistryService registryService;
    private final CaspAuthorizationRepository repository;

    CaspRegisterImportService(CaspRegistryService registryService,
                              CaspAuthorizationRepository repository) {
        this.registryService = registryService;
        this.repository = repository;
    }

    public record ImportResult(int created, int updated, int failed, List<String> errors) {}

    @Transactional
    public ImportResult importCsv(String csvContent, String source) {
        if (csvContent == null || csvContent.isBlank()) {
            return new ImportResult(0, 0, 0, List.of("Empty file."));
        }
        String[] lines = csvContent.split("\\R");
        char delimiter = detectDelimiter(lines[0]);
        Map<String, Integer> header = parseHeader(lines[0], delimiter);

        if (!header.containsKey("legal_name") || !header.containsKey("status")) {
            return new ImportResult(0, 0, 0, List.of(
                    "Missing required columns: expected at least 'legal_name' and 'status'. " +
                    "Found: " + header.keySet()));
        }

        int created = 0, updated = 0, failed = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            try {
                List<String> cells = splitCsvLine(line, delimiter);
                String legalName = cell(cells, header, "legal_name");
                String lei = cell(cells, header, "lei");
                String vaspDid = cell(cells, header, "vasp_did");
                if (isBlank(vaspDid)) {
                    if (isBlank(lei)) {
                        throw new IllegalArgumentException("Either vasp_did or lei must be present");
                    }
                    vaspDid = "lei:" + lei.trim().toUpperCase(Locale.ROOT);
                }
                if (isBlank(legalName)) {
                    throw new IllegalArgumentException("legal_name is required");
                }

                CaspAuthorization entry = new CaspAuthorization();
                entry.setVaspDid(vaspDid.trim());
                entry.setLegalName(legalName.trim());
                entry.setLei(trimToNull(lei));
                entry.setHomeMemberState(upperOrNull(cell(cells, header, "home_member_state")));
                entry.setStatus(mapStatus(cell(cells, header, "status")));
                entry.setAuthorizationId(trimToNull(cell(cells, header, "authorization_id")));
                entry.setValidFrom(parseDate(cell(cells, header, "valid_from")));
                entry.setValidUntil(parseDate(cell(cells, header, "valid_until")));
                entry.setNotes(trimToNull(cell(cells, header, "notes")));
                entry.setSource(source);

                boolean exists = repository.findByVaspDidIgnoreCase(entry.getVaspDid()).isPresent();
                registryService.upsert(entry);
                if (exists) {
                    updated++;
                } else {
                    created++;
                }
            } catch (Exception e) {
                failed++;
                if (errors.size() < MAX_REPORTED_ERRORS) {
                    errors.add("Line " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        log.info("CASP register import: {} created, {} updated, {} failed (source: {})",
                created, updated, failed, source);
        return new ImportResult(created, updated, failed, errors);
    }

    /** Tolerant status mapping — covers ESMA's British spelling and common variants. */
    static CaspAuthorizationStatus mapStatus(String raw) {
        if (isBlank(raw)) {
            throw new IllegalArgumentException("status is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "AUTHORIZED", "AUTHORISED", "GRANTED", "LICENSED", "LICENCED" -> CaspAuthorizationStatus.AUTHORIZED;
            case "TRANSITIONAL", "GRANDFATHERED", "TRANSITION", "TRANSITIONAL_PERIOD" -> CaspAuthorizationStatus.TRANSITIONAL;
            case "NOT_AUTHORIZED", "NOT_AUTHORISED", "PENDING", "REFUSED", "REJECTED" -> CaspAuthorizationStatus.NOT_AUTHORIZED;
            case "REVOKED", "WITHDRAWN", "LAPSED", "SUSPENDED" -> CaspAuthorizationStatus.REVOKED;
            default -> throw new IllegalArgumentException("Unknown status value: '" + raw + "'");
        };
    }

    private static char detectDelimiter(String headerLine) {
        long semis = headerLine.chars().filter(c -> c == ';').count();
        long commas = headerLine.chars().filter(c -> c == ',').count();
        return semis >= commas ? ';' : ',';
    }

    private static Map<String, Integer> parseHeader(String headerLine, char delimiter) {
        List<String> cells = splitCsvLine(headerLine, delimiter);
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String key = cells.get(i).trim().toLowerCase(Locale.ROOT)
                    .replace(' ', '_').replace("\ufeff", "");
            map.put(key, i);
        }
        return map;
    }

    /** Minimal RFC-4180 field splitter: supports quoted fields and doubled quotes. */
    static List<String> splitCsvLine(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private static String cell(List<String> cells, Map<String, Integer> header, String column) {
        Integer idx = header.get(column);
        if (idx == null || idx >= cells.size()) {
            return null;
        }
        return cells.get(idx);
    }

    private static LocalDate parseDate(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date '" + raw + "' — expected ISO format (YYYY-MM-DD)");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String upperOrNull(String s) {
        return isBlank(s) ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
