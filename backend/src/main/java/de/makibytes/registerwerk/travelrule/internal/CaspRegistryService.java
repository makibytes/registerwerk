package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.CaspAuthorizationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains the counterparty CASP authorization register and enforces the
 * MiCA transitional-period cutoff (Reg (EU) 2023/1114).
 *
 * <p>Per ESMA's statement of 17 April 2026, the MiCA transitional period ends
 * EU-wide on 1 July 2026. From that date, entities without a CASP authorization
 * may no longer provide crypto-asset services in the EU; transfers to such
 * counterparties must not be executed.
 *
 * <p>Enforcement rules applied by {@link #assertCounterpartyPermitted(String)}:
 * <ul>
 *   <li>{@code NOT_AUTHORIZED} / {@code REVOKED} — blocked at any date</li>
 *   <li>{@code TRANSITIONAL} — permitted before the enforcement date, blocked
 *       on or after it (no grandfathering beyond 1 July 2026)</li>
 *   <li>{@code AUTHORIZED} — permitted; if {@code validUntil} has passed, blocked</li>
 *   <li>no register entry — permitted with a warning (the counterparty may be a
 *       non-EU VASP outside MiCA's scope); compliance should record the status</li>
 * </ul>
 */
@Service
@Transactional
public class CaspRegistryService {

    private static final Logger log = LoggerFactory.getLogger(CaspRegistryService.class);

    private final CaspAuthorizationRepository repository;
    private final Clock clock;

    /** EU-wide end of the MiCA transitional period. */
    @Value("${registerwerk.travel-rule.mica-enforcement-date:2026-07-01}")
    private LocalDate micaEnforcementDate;

    CaspRegistryService(CaspAuthorizationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Verifies that an outbound transfer to the given counterparty VASP is
     * permitted under MiCA.
     *
     * @throws IllegalStateException if the counterparty must not be transacted with
     */
    @Transactional(readOnly = true)
    public void assertCounterpartyPermitted(String vaspDid) {
        Optional<CaspAuthorization> entry = repository.findByVaspDidIgnoreCase(vaspDid);
        if (entry.isEmpty()) {
            log.warn("MiCA check: no authorization record for counterparty VASP {} — " +
                    "permitted with warning; compliance should verify against the ESMA register.", vaspDid);
            return;
        }
        CaspAuthorization casp = entry.get();
        LocalDate today = LocalDate.now(clock);
        boolean afterCutoff = !today.isBefore(micaEnforcementDate);

        switch (casp.getStatus()) {
            case NOT_AUTHORIZED, REVOKED -> throw blocked(casp,
                    "is not authorized under MiCA (status " + casp.getStatus() + ")");
            case TRANSITIONAL -> {
                if (afterCutoff) {
                    throw blocked(casp, "was operating under a transitional regime, which ended on "
                            + micaEnforcementDate + " (ESMA: no grandfathering beyond this date)");
                }
            }
            case AUTHORIZED -> {
                if (casp.getValidUntil() != null && today.isAfter(casp.getValidUntil())) {
                    throw blocked(casp, "has an expired MiCA authorization (valid until "
                            + casp.getValidUntil() + ")");
                }
            }
        }
    }

    private IllegalStateException blocked(CaspAuthorization casp, String reason) {
        return new IllegalStateException("MiCA check: counterparty CASP " + casp.getLegalName()
                + " (" + casp.getVaspDid() + ") " + reason
                + " — the transfer must not be executed (Reg (EU) 2023/1114).");
    }

    @Transactional(readOnly = true)
    public List<CaspAuthorization> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<CaspAuthorization> findByVaspDid(String vaspDid) {
        return repository.findByVaspDidIgnoreCase(vaspDid);
    }

    public CaspAuthorization upsert(CaspAuthorization incoming) {
        CaspAuthorization target = repository.findByVaspDidIgnoreCase(incoming.getVaspDid())
                .orElse(incoming);
        if (target != incoming) {
            target.setLegalName(incoming.getLegalName());
            target.setLei(incoming.getLei());
            target.setHomeMemberState(incoming.getHomeMemberState());
            target.setStatus(incoming.getStatus());
            target.setAuthorizationId(incoming.getAuthorizationId());
            target.setValidFrom(incoming.getValidFrom());
            target.setValidUntil(incoming.getValidUntil());
            target.setSource(incoming.getSource());
            target.setNotes(incoming.getNotes());
        }
        return repository.save(target);
    }

    public void delete(UUID id) {
        repository.deleteById(id);
    }
}
