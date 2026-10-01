package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenRepository;
import de.makibytes.registerwerk.auth.api.SessionRevocationPort;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.api.ObligationAcknowledgement;
import de.makibytes.registerwerk.customer.api.OffboardingObligation;
import de.makibytes.registerwerk.customer.api.OffboardingObligationSource;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the customer off-ramp: previously {@code LegalEntityService.dissolveEntity} (the
 * only "exit" path) flipped one enum field and cascaded to nothing — a dissolved entity's users
 * stayed enabled, its open trade listings stayed live, its ASSET_TOKEN_ADMIN grants stayed
 * active, and nothing signalled that its issued assets or investor holdings needed any
 * follow-up. This service is the real off-ramp: it owns the parts that belong to the
 * {@code customer} module directly (disabling users, the terminal status transition) and
 * publishes {@link CustomerOffboardedEvent} for every other module's own cleanup — asset grants,
 * trade listings, and portfolio-migration requests — following the same event-driven pattern as
 * {@code AssetRedeemedEvent}/{@code AssetRedemptionListener} rather than reaching across module
 * boundaries directly (customer has no dependency on asset/trading/registertransfer, and must
 * not gain one — those modules already depend on customer, so the reverse edge would cycle).
 */
@Service
@Transactional
public class CustomerOffboardingService {

    private static final Logger log = LoggerFactory.getLogger(CustomerOffboardingService.class);

    private final LegalEntityRepository entityRepository;
    private final AppUserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final AppUserActionTokenRepository actionTokenRepository;
    private final SessionRevocationPort sessionRevocation;
    private final EntityTaskPort taskPort;
    private final List<OffboardingObligationSource> obligationSources;

    public CustomerOffboardingService(LegalEntityRepository entityRepository,
                                       AppUserRepository userRepository,
                                       ApplicationEventPublisher events,
                                       AppUserActionTokenRepository actionTokenRepository,
                                       SessionRevocationPort sessionRevocation,
                                       EntityTaskPort taskPort,
                                       List<OffboardingObligationSource> obligationSources) {
        this.entityRepository = entityRepository;
        this.userRepository = userRepository;
        this.events = events;
        this.actionTokenRepository = actionTokenRepository;
        this.sessionRevocation = sessionRevocation;
        this.taskPort = taskPort;
        this.obligationSources = obligationSources == null ? List.of() : obligationSources;
    }

    /** The open obligations of an entity, as the termination pre-check (and the operator UI) sees them. */
    @Transactional(readOnly = true)
    public List<OffboardingObligation> openObligations(UUID entityId) {
        List<OffboardingObligation> all = new ArrayList<>();
        for (OffboardingObligationSource source : obligationSources) {
            all.addAll(source.openObligations(entityId));
        }
        return all;
    }

    /** Termination without any acknowledged obligation: refused while obligations are open. */
    public LegalEntity terminate(UUID entityId, UUID actorId, String actorRole, String reason) {
        return terminate(entityId, actorId, actorRole, reason, List.of());
    }

    /**
     * Terminates the customer (ACTIVE/SUSPENDED -&gt; CLOSED). Every open obligation (issued
     * securities, open trades, repo/lending positions, pending corporate actions and register
     * transfers, Sperrvermerk holdings) must be acknowledged with a reason, otherwise a
     * {@link ComplianceGateException} lists them (409). Acknowledged obligations are persisted as
     * follow-up tasks that stay OPEN (and alert) until an operator closes them. The entity's users
     * are disabled, their unconsumed action tokens burnt and their sessions revoked.
     */
    public LegalEntity terminate(UUID entityId, UUID actorId, String actorRole, String reason,
                                 List<ObligationAcknowledgement> acknowledgements) {
        LegalEntity entity = load(entityId);
        if (!entity.getStatus().canTransitionTo(EntityStatus.CLOSED)) {
            throw new InvalidStateTransitionException("LegalEntity", entity.getStatus().name(), EntityStatus.CLOSED.name());
        }

        List<OffboardingObligation> obligations = openObligations(entityId);
        Map<String, String> acked = new LinkedHashMap<>();
        for (ObligationAcknowledgement ack : acknowledgements == null ? List.<ObligationAcknowledgement>of() : acknowledgements) {
            if (ack.reason() == null || ack.reason().isBlank()) {
                throw new IllegalArgumentException("Acknowledgement of " + ack.obligationId() + " needs a reason");
            }
            acked.put(ack.obligationId(), ack.reason().trim());
        }
        List<OffboardingObligation> unacknowledged = obligations.stream()
                .filter(o -> !acked.containsKey(o.id())).toList();
        if (!unacknowledged.isEmpty()) {
            throw new ComplianceGateException("Entity " + entityId + " has open obligations that must be acknowledged "
                    + "(acknowledgedObligations: obligationId + reason) before it can be terminated: "
                    + unacknowledged.stream().map(o -> o.id() + " (" + o.description() + ")")
                            .collect(java.util.stream.Collectors.joining("; ")));
        }

        int disabledCount = shutOutUsers(entityId);
        for (OffboardingObligation o : obligations) {
            taskPort.open(entityId, o.kind(), o.refId(),
                    o.description() + " - acknowledged at termination: " + acked.get(o.id()), actorId);
        }

        entity.setStatus(EntityStatus.CLOSED);
        LegalEntity saved = entityRepository.save(entity);

        events.publishEvent(new CustomerOffboardedEvent(entityId, actorId, actorRole, reason, "CLOSED",
                obligations.stream().map(OffboardingObligation::id).toList()));
        log.warn("Customer offboarded: entityId={} usersDisabled={} obligations={} reason={}",
                entityId, disabledCount, obligations.size(), reason);
        return saved;
    }

    /**
     * Off-ramp of the source of a merger (called from {@code LegalEntityService.mergeEntities}):
     * same shut-out and event as a termination but the final status is DISSOLVED, and open
     * obligations do not block - they become follow-up tasks naming the successor.
     */
    public LegalEntity dissolveByMerger(UUID sourceId, UUID targetId, UUID actorId, String actorRole, String reason) {
        LegalEntity entity = load(sourceId);
        if (!entity.getStatus().canTransitionTo(EntityStatus.DISSOLVED)) {
            throw new InvalidStateTransitionException("LegalEntity", entity.getStatus().name(), EntityStatus.DISSOLVED.name());
        }
        List<OffboardingObligation> obligations = openObligations(sourceId);
        shutOutUsers(sourceId);
        for (OffboardingObligation o : obligations) {
            taskPort.open(sourceId, o.kind(), o.refId(),
                    o.description() + " - entity merged into " + targetId + "; transfer to the successor", actorId);
        }
        entity.setStatus(EntityStatus.DISSOLVED);
        LegalEntity saved = entityRepository.save(entity);
        events.publishEvent(new CustomerOffboardedEvent(sourceId, actorId, actorRole, "Merged into " + targetId + ": " + reason,
                "DISSOLVED", obligations.stream().map(OffboardingObligation::id).toList()));
        log.warn("Entity dissolved by merger: source={} target={} obligations={}", sourceId, targetId, obligations.size());
        return saved;
    }

    private LegalEntity load(UUID entityId) {
        return entityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
    }

    /** Disables the users, burns their unconsumed action tokens and revokes their sessions. */
    private int shutOutUsers(UUID entityId) {
        int disabledCount = 0;
        for (AppUser user : userRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId)) {
            if (user.isEnabled()) {
                user.setEnabled(false);
                userRepository.save(user);
                disabledCount++;
            }
            if (user.getId() != null) {
                actionTokenRepository.invalidateAllForUser(user.getId());
                sessionRevocation.revokeAll(user.getId());
            }
        }
        return disabledCount;
    }
}
