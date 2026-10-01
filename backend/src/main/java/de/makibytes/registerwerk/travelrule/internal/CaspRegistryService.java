package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.CaspAuthorizationStatus;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort.VaspInfo;
import de.makibytes.registerwerk.travelrule.events.CaspAuthorizationDeletedEvent;
import de.makibytes.registerwerk.travelrule.events.CaspAuthorizationUpsertedEvent;
import de.makibytes.registerwerk.travelrule.events.TravelRuleControlEvent;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>Counterparty lookup order (6-28): provider DID, then LEI (ESMA rows are keyed {@code lei:<LEI>}),
 * then the normalised legal name when it identifies exactly one row.
 *
 * <p>Enforcement rules applied by {@link #assertCounterpartyPermitted(VaspInfo)}:
 * <ul>
 *   <li>{@code NOT_AUTHORIZED} / {@code REVOKED} - blocked at any date</li>
 *   <li>{@code TRANSITIONAL} - permitted before the enforcement date, blocked on or after it</li>
 *   <li>{@code AUTHORIZED} - permitted; if {@code validUntil} has passed, blocked</li>
 *   <li>{@code THIRD_COUNTRY_REVIEWED} - permitted only while the recorded due-diligence review has not
 *       expired ({@code validUntil} is mandatory)</li>
 *   <li>no register entry - permitted with a warning <em>before</em> the enforcement date, blocked on or
 *       after it (parked T6-07: fail closed, non-EU VASPs need a reviewed row)</li>
 * </ul>
 */
@Service
@Transactional
public class CaspRegistryService {

    private static final Logger log = LoggerFactory.getLogger(CaspRegistryService.class);
    private static final Set<CaspAuthorizationStatus> BLOCKING =
            Set.of(CaspAuthorizationStatus.NOT_AUTHORIZED, CaspAuthorizationStatus.REVOKED);

    private final CaspAuthorizationRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbc;

    /** EU-wide end of the MiCA transitional period. */
    @Value("${registerwerk.travel-rule.mica-enforcement-date:2026-07-01}")
    private LocalDate micaEnforcementDate;

    CaspRegistryService(CaspAuthorizationRepository repository, Clock clock, ApplicationEventPublisher eventPublisher,
                        JdbcTemplate jdbc) {
        this.repository = repository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.jdbc = jdbc;
    }

    /** DID-only convenience (tests, legacy callers). */
    public void assertCounterpartyPermitted(String vaspDid) {
        assertCounterpartyPermitted(new VaspInfo(vaspDid, null, null, null, null));
    }

    /**
     * Verifies that an outbound transfer to the given counterparty VASP is permitted under MiCA.
     *
     * @throws ComplianceGateException if the counterparty must not be transacted with
     */
    public void assertCounterpartyPermitted(VaspInfo counterparty) {
        check(counterparty, false);
    }

    /**
     * Inbound sender check: blocked/revoked/expired register rows are refused. An absent row is accepted,
     * because the sender is an authenticated peer that an administrator registered with a second approver.
     */
    public void assertInboundSenderPermitted(VaspInfo sender) {
        check(sender, true);
    }

    private void check(VaspInfo counterparty, boolean allowUnknown) {
        Optional<CaspAuthorization> entry = lookup(counterparty);
        LocalDate today = LocalDate.now(clock);
        boolean afterCutoff = !today.isBefore(micaEnforcementDate);
        if (entry.isEmpty()) {
            String label = describe(counterparty);
            if (allowUnknown) {
                return;
            }
            if (afterCutoff) {
                throw new ComplianceGateException("MiCA check: counterparty VASP " + label
                        + " is not in the CASP authorization register - after " + micaEnforcementDate
                        + " the transfer must not be executed (Reg (EU) 2023/1114). Add the ESMA/NCA entry, or for a "
                        + "non-EU VASP record a reviewed third-country entry (status THIRD_COUNTRY_REVIEWED).");
            }
            log.warn("MiCA check: no authorization record for counterparty VASP {} - permitted before {}; " +
                    "compliance should verify against the ESMA register.", label, micaEnforcementDate);
            return;
        }
        CaspAuthorization casp = entry.get();
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
            case THIRD_COUNTRY_REVIEWED -> {
                if (casp.getValidUntil() == null || today.isAfter(casp.getValidUntil())) {
                    throw blocked(casp, "has no current third-country due-diligence review (valid until "
                            + casp.getValidUntil() + ")");
                }
            }
        }
    }

    private static String describe(VaspInfo v) {
        return (v.vaspId() != null ? v.vaspId() : "?") + (v.lei() != null ? " (LEI " + v.lei() + ")" : "")
                + (v.legalName() != null && !v.legalName().isBlank() ? " '" + v.legalName() + "'" : "");
    }

    private ComplianceGateException blocked(CaspAuthorization casp, String reason) {
        return new ComplianceGateException("MiCA check: counterparty CASP " + casp.getLegalName()
                + " (" + casp.getVaspDid() + ") " + reason
                + " - the transfer must not be executed (Reg (EU) 2023/1114).");
    }

    /** DID, then LEI, then a unique normalised legal name. Persists the provider DID on first sight of an LEI row. */
    Optional<CaspAuthorization> lookup(VaspInfo v) {
        String did = trim(v.vaspId());
        if (did != null) {
            Optional<CaspAuthorization> byDid = repository.findByVaspDidIgnoreCase(did);
            if (byDid.isPresent()) {
                return byDid;
            }
        }
        String lei = trim(v.lei());
        if (lei == null && did != null && did.regionMatches(true, 0, "lei:", 0, 4)) {
            lei = did.substring(4);
        }
        if (lei != null) {
            Optional<CaspAuthorization> byLei = repository.findByLeiIgnoreCase(lei);
            if (byLei.isPresent()) {
                reconcileDid(byLei.get(), did);
                return byLei;
            }
        }
        String name = normalizeName(v.legalName());
        if (name != null) {
            List<CaspAuthorization> matches = repository.findAll().stream()
                    .filter(c -> name.equals(normalizeName(c.getLegalName()))).toList();
            if (matches.size() == 1) {
                return Optional.of(matches.get(0));
            }
        }
        return Optional.empty();
    }

    private void reconcileDid(CaspAuthorization row, String providerDid) {
        if (providerDid == null || providerDid.regionMatches(true, 0, "lei:", 0, 4)
                || providerDid.equalsIgnoreCase(row.getVaspDid())) {
            return;
        }
        if (row.getVaspDid().regionMatches(true, 0, "lei:", 0, 4)) {
            if (repository.findByVaspDidIgnoreCase(providerDid).isEmpty()) {
                row.setVaspDid(providerDid);
                repository.save(row);
                log.info("CASP register: recorded provider DID {} on LEI row {}", providerDid, row.getLei());
            }
        } else {
            log.warn("CASP register: provider DID {} differs from register DID {} for LEI {}",
                    providerDid, row.getVaspDid(), row.getLei());
            eventPublisher.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.CASP_DID_MISMATCH,
                    "CaspAuthorization", row.getId(), null, "SYSTEM",
                    Map.of("registerDid", row.getVaspDid(), "providerDid", providerDid, "lei", String.valueOf(row.getLei()))));
        }
    }

    static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return n.isEmpty() ? null : n;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Transactional(readOnly = true)
    public List<CaspAuthorization> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<CaspAuthorization> findByVaspDid(String vaspDid) {
        return repository.findByVaspDidIgnoreCase(vaspDid);
    }

    /** Existing row for an incoming entry: by DID, then by LEI. */
    Optional<CaspAuthorization> findExisting(CaspAuthorization incoming) {
        Optional<CaspAuthorization> byDid = repository.findByVaspDidIgnoreCase(incoming.getVaspDid());
        if (byDid.isPresent() || incoming.getLei() == null) {
            return byDid;
        }
        return repository.findByLeiIgnoreCase(incoming.getLei());
    }

    /** Is {@code userId} a REGISTRY_ADMIN? Used for the extra approver requirement on weakening changes. */
    @Transactional(readOnly = true)
    public boolean isRegistryAdmin(UUID userId) {
        if (userId == null) {
            return false;
        }
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM app_user u WHERE u.id = ? AND u.enabled
               AND (u.role = 'REGISTRY_ADMIN'
                    OR EXISTS (SELECT 1 FROM app_user_role r WHERE r.app_user_id = u.id AND r.role = 'REGISTRY_ADMIN'))
            """, Integer.class, userId);
        return n != null && n > 0;
    }

    /** True when replacing {@code existing.status} by {@code incoming} lifts a blocking status. */
    static boolean weakensBlock(CaspAuthorizationStatus existing, CaspAuthorizationStatus incoming) {
        return BLOCKING.contains(existing) && !BLOCKING.contains(incoming);
    }

    public CaspAuthorization upsert(CaspAuthorization incoming) {
        return upsert(incoming, null, null, null);
    }

    public CaspAuthorization upsert(CaspAuthorization incoming, UUID actorId, String actorRole) {
        return upsert(incoming, actorId, actorRole, null);
    }

    /**
     * @param approverId second approver validated by the controller's step-up; mandatory for entries
     *                   created/changed through the API (null only for internal/system callers and tests)
     */
    public CaspAuthorization upsert(CaspAuthorization incoming, UUID actorId, String actorRole, UUID approverId) {
        if (incoming.getStatus() == CaspAuthorizationStatus.THIRD_COUNTRY_REVIEWED) {
            if (incoming.getValidUntil() == null) {
                throw new IllegalArgumentException("A third-country review needs an expiry (validUntil)");
            }
            if (actorId == null || approverId == null) {
                throw new AccessDeniedException("A third-country review needs a reviewer and a second approver");
            }
            incoming.setReviewedBy(actorId);
            incoming.setSecondApproverId(approverId);
        }
        Optional<CaspAuthorization> existing = findExisting(incoming);
        if (existing.isPresent() && approverId != null
                && weakensBlock(existing.get().getStatus(), incoming.getStatus())
                && !isRegistryAdmin(approverId)) {
            throw new AccessDeniedException("Lifting a NOT_AUTHORIZED/REVOKED status needs a REGISTRY_ADMIN as second approver");
        }
        if (incoming.getLei() != null) {
            Optional<CaspAuthorization> sameLei = repository.findByLeiIgnoreCase(incoming.getLei());
            if (sameLei.isPresent() && existing.isPresent() && !sameLei.get().getId().equals(existing.get().getId())) {
                throw new IllegalArgumentException("LEI " + incoming.getLei() + " already belongs to another register entry");
            }
        }
        CaspAuthorization target = existing.orElse(incoming);
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
            target.setCountry(incoming.getCountry());
            target.setReviewedBy(incoming.getReviewedBy());
            target.setSecondApproverId(incoming.getSecondApproverId());
            // A real DID supplied by the operator replaces a synthetic lei:<LEI> key; an ESMA re-import
            // (synthetic key) never overwrites a DID learned from the provider.
            boolean incomingSynthetic = incoming.getVaspDid().regionMatches(true, 0, "lei:", 0, 4);
            if (!incomingSynthetic && !incoming.getVaspDid().equalsIgnoreCase(target.getVaspDid())) {
                target.setVaspDid(incoming.getVaspDid());
            }
        }
        CaspAuthorization saved = repository.save(target);
        eventPublisher.publishEvent(new CaspAuthorizationUpsertedEvent(saved.getId(), actorId, actorRole, Map.of(
                "vaspDid", saved.getVaspDid(), "status", saved.getStatus().name(),
                "previousStatus", existing.map(e -> e.getStatus().name()).orElse("NONE")), approverId));
        return saved;
    }

    public void delete(UUID id, UUID actorId, String actorRole) {
        delete(id, actorId, actorRole, null);
    }

    public void delete(UUID id, UUID actorId, String actorRole, UUID approverId) {
        CaspAuthorization row = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("CaspAuthorization", id));
        if (BLOCKING.contains(row.getStatus()) && approverId != null && !isRegistryAdmin(approverId)) {
            throw new AccessDeniedException(
                    "Deleting a NOT_AUTHORIZED/REVOKED entry needs a REGISTRY_ADMIN as second approver");
        }
        repository.deleteById(id);
        eventPublisher.publishEvent(new CaspAuthorizationDeletedEvent(id, actorId, actorRole,
                Map.of("vaspDid", row.getVaspDid(), "status", row.getStatus().name()), approverId));
    }
}
