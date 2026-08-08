package de.makibytes.registerwerk.registerstatement.web;

import de.makibytes.registerwerk.registerstatement.api.RegisterStatement;
import de.makibytes.registerwerk.registerstatement.api.RegisterStatementRepository;
import de.makibytes.registerwerk.registerstatement.internal.RegisterStatementService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * §19 eWpG register statement endpoints.
 *
 * <ul>
 *   <li>{@code POST /holders/{holderId}/issue} — operator issues an on-demand
 *       statement (§19(1)).</li>
 *   <li>{@code GET /investors/{investorId}} — statement history for an investor
 *       (operator view / investor self-service audit trail).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/register-statements")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
@Validated
public class RegisterStatementController {

    private final RegisterStatementService statementService;
    private final RegisterStatementRepository statementRepository;

    RegisterStatementController(RegisterStatementService statementService,
                                RegisterStatementRepository statementRepository) {
        this.statementService = statementService;
        this.statementRepository = statementRepository;
    }

    @PostMapping("/holders/{holderId}/issue")
    public ResponseEntity<RegisterStatement> issueOnDemand(@PathVariable UUID holderId) {
        return statementService.issueOnDemand(holderId)
                .map(ResponseEntity::ok)
                // Holder not eligible (not single entry) — 409 rather than 404,
                // the holder exists but a statement cannot be issued for it.
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @GetMapping("/investors/{investorId}")
    public Page<RegisterStatement> listForInvestor(
            @PathVariable UUID investorId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return statementRepository.findByInvestorIdOrderByIssuedAtDesc(
                investorId, PageRequest.of(page, size));
    }
}
