package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.events.OrgRegisteredEvent;
import de.makibytes.registerwerk.orgidentity.events.OrgReinstatedEvent;
import de.makibytes.registerwerk.orgidentity.events.OrgSuspendedEvent;
import de.makibytes.registerwerk.shared.AfterCommit;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Registers legal entities as organizations in the onchain OrgRegistry, keyed by their
 * ONCHAINID. Suspension and reinstatement mirror the operator's regulatory powers.
 */
@Service
@Transactional
public class OrgRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(OrgRegistrationService.class);

    static final String PENDING_IDENTITY_PREFIX = "0x-PENDING-";

    private final OrgRegistrationRepository registrationRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final Erc3643Api erc3643Api;
    private final EcosystemTxGateway txGateway;
    private final EcosystemOnchainBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;

    OrgRegistrationService(
            OrgRegistrationRepository registrationRepository,
            LegalEntityRepository legalEntityRepository,
            Erc3643Api erc3643Api,
            EcosystemTxGateway txGateway,
            EcosystemOnchainBroadcaster broadcaster,
            ApplicationEventPublisher eventPublisher) {
        this.registrationRepository = registrationRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.erc3643Api = erc3643Api;
        this.txGateway = txGateway;
        this.broadcaster = broadcaster;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Registers a legal entity as an organization on a chain. Deploys the entity's
     * ONCHAINID first if needed; while the identity deploy is pending, the registration
     * stays PENDING and {@link OrgEcosystemTxPoller} submits {@code registerOrg} once
     * the identity address resolves.
     *
     * @param countryCode ISO-3166-1 numeric country code; null if unknown
     */
    public OrgRegistration register(UUID legalEntityId, UUID chainConfigId, Short countryCode,
                                    UUID actorId, String actorRole) {
        var legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", legalEntityId));
        // Anchoring an org identity is the trust root everything downstream (permissions,
        // marketplace publishing) relies on — an entity that never passed KYC (or whose KYC
        // has expired) must not be allowed to register one.
        if (legalEntity.getKycStatus() != de.makibytes.registerwerk.customer.api.KycStatus.APPROVED) {
            throw new InvalidStateTransitionException(
                    "Legal entity " + legalEntityId + " has not passed KYC (status="
                            + legalEntity.getKycStatus() + ") — cannot register an onchain organization");
        }
        ChainConfig chainConfig = txGateway.requireChain(chainConfigId);

        registrationRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId)
                .ifPresent(existing -> {
                    throw new InvalidStateTransitionException(
                            "Org registration already exists for entity " + legalEntityId
                            + " on chain " + chainConfig.getIdentifier() + " (status " + existing.getStatus() + ")");
                });

        OnchainIdentity identity = erc3643Api.getOrCreateIdentity(legalEntityId, chainConfigId, actorId, actorRole);

        OrgRegistration registration = new OrgRegistration();
        registration.setLegalEntityId(legalEntityId);
        registration.setChainConfigId(chainConfigId);
        registration.setOrgAddress(identity.getIdentityAddress());
        registration.setStatus(OrgRegistrationStatus.PENDING);
        registration.setCountryCode(countryCode);

        if (identity.getIdentityAddress().startsWith(PENDING_IDENTITY_PREFIX)) {
            log.info("ONCHAINID for entity={} on chain={} still deploying; registerOrg deferred to poller",
                    legalEntityId, chainConfigId);
        }

        OrgRegistration saved = registrationRepository.save(registration);
        // The chain tx is broadcast only once the PENDING row is committed — a rollback
        // (e.g. failed audit write) must never leave an irreversible tx behind. The
        // poller retries rows whose broadcast failed.
        AfterCommit.run(() -> broadcaster.broadcastRegisterOrg(saved.getId()));
        eventPublisher.publishEvent(new OrgRegisteredEvent(saved.getId(), actorId, actorRole, Map.of(
                "legalEntityId", legalEntityId.toString(),
                "chainConfigId", chainConfigId.toString(),
                "orgAddress", saved.getOrgAddress()
        )));
        return saved;
    }

    /** Persists a fail-closed suspend intent. SUSPENDED is written only by the receipt poller. */
    public OrgRegistration suspend(UUID registrationId, String reason, UUID actorId, String actorRole) {
        OrgRegistration registration = require(registrationId);
        txGateway.requireChain(registration.getChainConfigId());
        if (registration.getStatus() != OrgRegistrationStatus.ACTIVE
                && registration.getStatus() != OrgRegistrationStatus.SUSPEND_FAILED) {
            throw new InvalidStateTransitionException(
                    "Org registration " + registrationId + " is " + registration.getStatus()
                            + "; only ACTIVE or a failed suspension can be suspended");
        }

        registration.setStatus(OrgRegistrationStatus.SUSPEND_PENDING);
        registration.setSuspendedAt(Instant.now());
        registration.setSuspendedBy(actorId);
        registration.setSuspensionReason(reason);
        clearStatusCausality(registration);
        registration.setStatusRequestedAt(Instant.now());
        OrgRegistration saved = registrationRepository.save(registration);
        AfterCommit.run(() -> broadcaster.broadcastOrgStatus(saved.getId(), reason));

        eventPublisher.publishEvent(new OrgSuspendedEvent(saved.getId(), saved.getLegalEntityId(),
                actorId, actorRole, Map.of("reason", reason)));
        return saved;
    }

    /** Persists a fail-closed reinstate intent. ACTIVE is written only by the receipt poller. */
    public OrgRegistration reinstate(UUID registrationId, UUID actorId, String actorRole) {
        OrgRegistration registration = require(registrationId);
        txGateway.requireChain(registration.getChainConfigId());
        if (registration.getStatus() != OrgRegistrationStatus.SUSPENDED
                && registration.getStatus() != OrgRegistrationStatus.REINSTATE_FAILED) {
            throw new InvalidStateTransitionException(
                    "Org registration " + registrationId + " is " + registration.getStatus()
                            + "; only SUSPENDED or a failed reinstatement can be reinstated");
        }

        registration.setStatus(OrgRegistrationStatus.REINSTATE_PENDING);
        clearStatusCausality(registration);
        registration.setStatusRequestedAt(Instant.now());
        OrgRegistration saved = registrationRepository.save(registration);
        AfterCommit.run(() -> broadcaster.broadcastOrgStatus(saved.getId(), null));

        eventPublisher.publishEvent(new OrgReinstatedEvent(saved.getId(), saved.getLegalEntityId(),
                actorId, actorRole, Map.of()));
        return saved;
    }

    private static void clearStatusCausality(OrgRegistration registration) {
        registration.setStatusTx(null);
        registration.setStatusChainConfigId(null);
        registration.setStatusBlockNumber(null);
        registration.setStatusBlockHash(null);
    }

    public OrgRegistration require(UUID registrationId) {
        return registrationRepository.findById(registrationId)
                .orElseThrow(() -> new EntityNotFoundException("OrgRegistration", registrationId));
    }
}
