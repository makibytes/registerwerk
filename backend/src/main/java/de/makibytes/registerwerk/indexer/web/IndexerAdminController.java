package de.makibytes.registerwerk.indexer.web;

import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.internal.IndexerAdminService;
import de.makibytes.registerwerk.indexer.web.dto.IndexerStateResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Operator visibility and recovery surface for {@code indexer_state} — previously an indexer
 * stuck in {@code ERROR} had no application-level path back to {@code ACTIVE}; see
 * {@link IndexerAdminService}'s Javadoc for the manual-SQL workaround this replaces.
 */
@RestController
@RequestMapping("/api/v1/indexers")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER','AUDIT')")
public class IndexerAdminController {

    private final IndexerStateRepository repository;
    private final IndexerAdminService adminService;

    public IndexerAdminController(IndexerStateRepository repository, IndexerAdminService adminService) {
        this.repository = repository;
        this.adminService = adminService;
    }

    @GetMapping
    public ResponseEntity<List<IndexerStateResponse>> list() {
        List<IndexerStateResponse> body = repository.findAll().stream()
                .map(IndexerStateResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /**
     * Clears a stuck indexer's error state so it resumes on its next scheduled tick.
     * {@code fullResync=true} also discards the existing cursor, forcing a full re-sync from
     * genesis — see {@link IndexerAdminService#reset} for when that's actually appropriate.
     */
    @PostMapping("/{id}/reset")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<IndexerStateResponse> reset(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean fullResync,
            Authentication auth) {
        IndexerState reset = adminService.reset(
                id, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), fullResync);
        return ResponseEntity.ok(IndexerStateResponse.from(reset));
    }
}
