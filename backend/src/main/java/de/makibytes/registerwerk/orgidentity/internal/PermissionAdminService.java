package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantType;
import de.makibytes.registerwerk.orgidentity.events.PermissionDefinitionCreatedEvent;
import de.makibytes.registerwerk.orgidentity.events.PermissionGrantedEvent;
import de.makibytes.registerwerk.orgidentity.events.PermissionRevokedEvent;
import de.makibytes.registerwerk.orgidentity.events.PermissionRoleRestrictionChangedEvent;
import de.makibytes.registerwerk.orgidentity.events.TrustedIssuerChangedEvent;
import de.makibytes.registerwerk.shared.AfterCommit;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administers the permission framework mirror: permission definitions (DB metadata;
 * grants are the onchain source of truth), operator org-grants, org-admin role
 * delegations (relayed via the operator wallet after JWT authorization), and the
 * ecosystem-wide trusted claim issuers.
 */
@Service
@Transactional
public class PermissionAdminService {

    private static final String PERMISSION_CODE_PATTERN = "^[a-z0-9-]+(\\.[a-z0-9-]+)+$";

    private final PermissionDefinitionRepository definitionRepository;
    private final PermissionGrantRepository grantRepository;
    private final OrgRegistrationRepository registrationRepository;
    private final EcosystemTrustedIssuerRepository trustedIssuerRepository;
    private final EcosystemTxGateway txGateway;
    private final EcosystemOnchainBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;

    PermissionAdminService(
            PermissionDefinitionRepository definitionRepository,
            PermissionGrantRepository grantRepository,
            OrgRegistrationRepository registrationRepository,
            EcosystemTrustedIssuerRepository trustedIssuerRepository,
            EcosystemTxGateway txGateway,
            EcosystemOnchainBroadcaster broadcaster,
            ApplicationEventPublisher eventPublisher) {
        this.definitionRepository = definitionRepository;
        this.grantRepository = grantRepository;
        this.registrationRepository = registrationRepository;
        this.trustedIssuerRepository = trustedIssuerRepository;
        this.txGateway = txGateway;
        this.broadcaster = broadcaster;
        this.eventPublisher = eventPublisher;
    }

    // ── Definitions ───────────────────────────────────────────────────────────

    /**
     * Creates a permission definition. Definitions are platform metadata; the onchain
     * source of truth is the grant state in the per-chain PermissionRegistry.
     */
    public PermissionDefinition define(String code, String name, String description,
                                       UUID dappListingId, PermissionDefinitionStatus status,
                                       UUID actorId, String actorRole) {
        String normalized = code.trim().toLowerCase();
        if (!normalized.matches(PERMISSION_CODE_PATTERN)) {
            throw new IllegalArgumentException(
                    "Permission code must be namespaced like '<dapp>.<action>' (lowercase, digits, hyphens)");
        }
        if (definitionRepository.existsByCode(normalized)) {
            throw new InvalidStateTransitionException("Permission '" + normalized + "' already exists");
        }

        PermissionDefinition definition = new PermissionDefinition();
        definition.setCode(normalized);
        definition.setPermissionHash(EcosystemAbi.codeHashHex(normalized));
        definition.setName(name);
        definition.setDescription(description);
        definition.setDappListingId(dappListingId);
        definition.setStatus(status);
        PermissionDefinition saved = definitionRepository.save(definition);

        eventPublisher.publishEvent(new PermissionDefinitionCreatedEvent(saved.getId(), actorId, actorRole,
                Map.of("code", normalized)));
        return saved;
    }

    // ── Operator tier: org grants ─────────────────────────────────────────────

    public PermissionGrant grantToOrg(UUID definitionId, UUID orgRegistrationId,
                                      UUID actorId, String actorRole, UUID dualControlApproverId) {
        PermissionDefinition definition = requireDefinition(definitionId);
        OrgRegistration org = requireActiveOrg(orgRegistrationId);

        grantRepository.findByPermissionDefinitionIdAndOrgRegistrationIdAndGrantTypeAndStatus(
                        definitionId, orgRegistrationId, PermissionGrantType.ORG, PermissionGrantStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new InvalidStateTransitionException("Org already holds '" + definition.getCode() + "'");
                });

        PermissionGrant grant = new PermissionGrant();
        grant.setPermissionDefinitionId(definitionId);
        grant.setOrgRegistrationId(orgRegistrationId);
        grant.setGrantType(PermissionGrantType.ORG);
        grant.setStatus(PermissionGrantStatus.PENDING);
        grant.setDualControlApproverId(dualControlApproverId);
        grant.setDualControlApprovedAt(dualControlApproverId != null ? Instant.now() : null);
        PermissionGrant saved = grantRepository.save(grant);
        // Chain tx follows the commit (poller retries committed rows without a tx hash).
        AfterCommit.run(() -> broadcaster.broadcastGrant(saved.getId()));

        eventPublisher.publishEvent(new PermissionGrantedEvent(saved.getId(), actorId, actorRole, dualControlApproverId, Map.of(
                "permission", definition.getCode(),
                "orgRegistrationId", orgRegistrationId.toString(),
                "grantType", "ORG"
        )));
        return saved;
    }

    /**
     * Revokes any grant, operator-tier (ORG) or org-admin-tier (ROLE) alike. Reserved for the
     * operator-facing path ({@code PermissionAdminController}, step-up gated) — company-side
     * callers must go through {@link #revokeRoleGrant} instead, which additionally rejects
     * ORG-type grants, so a COMPANY_ADMIN can never revoke an operator-granted permission just
     * because they can see its id in {@code myGrants()}.
     */
    public PermissionGrant revokeGrant(UUID grantId, UUID expectedEntityId, UUID actorId, String actorRole,
                                       UUID dualControlApproverId) {
        return revokeGrantInternal(grantId, expectedEntityId, null, actorId, actorRole, dualControlApproverId);
    }

    /**
     * Revokes a role-tier (org-admin-delegated) grant only — the company-side counterpart to
     * {@link #revokeGrant}. Rejects a grant whose {@code grantType} is {@code ORG} with the same
     * {@link EntityNotFoundException} used for "doesn't exist"/"doesn't belong to your org", so a
     * COMPANY_ADMIN probing grant ids learns nothing about which ones are operator-granted.
     *
     * <p>Previously {@code CompanyOrgIdentityController.revokeRoleGrant} called the untyped
     * {@link #revokeGrant} directly — since {@code myGrants()} returns both ORG and ROLE grants
     * for the caller's own org, any COMPANY_ADMIN could revoke an operator-granted ORG permission
     * through this no-step-up endpoint, fully bypassing {@code PermissionAdminController
     * .revokeGrant}'s step-up-gated operator path.
     */
    public PermissionGrant revokeRoleGrant(UUID grantId, UUID expectedEntityId, UUID actorId, String actorRole) {
        return revokeGrantInternal(grantId, expectedEntityId, PermissionGrantType.ROLE, actorId, actorRole, null);
    }

    private PermissionGrant revokeGrantInternal(UUID grantId, UUID expectedEntityId,
                                                PermissionGrantType requiredType, UUID actorId, String actorRole,
                                                UUID dualControlApproverId) {
        PermissionGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new EntityNotFoundException("PermissionGrant", grantId));
        if (requiredType != null && grant.getGrantType() != requiredType) {
            throw new EntityNotFoundException("PermissionGrant", grantId);
        }
        if (grant.getStatus() == PermissionGrantStatus.REVOKED) {
            throw new InvalidStateTransitionException("Grant already revoked");
        }
        PermissionDefinition definition = requireDefinition(grant.getPermissionDefinitionId());
        OrgRegistration org = registrationRepository.findById(grant.getOrgRegistrationId())
                .orElseThrow(() -> new EntityNotFoundException("OrgRegistration", grant.getOrgRegistrationId()));
        if (expectedEntityId != null && !org.getLegalEntityId().equals(expectedEntityId)) {
            throw new EntityNotFoundException("PermissionGrant", grantId);
        }

        grant.setStatus(PermissionGrantStatus.REVOKED);
        grant.setRevokedAt(Instant.now());
        if (dualControlApproverId != null) {
            grant.setDualControlApproverId(dualControlApproverId);
            grant.setDualControlApprovedAt(Instant.now());
        }
        PermissionGrant saved = grantRepository.save(grant);
        AfterCommit.run(() -> broadcaster.broadcastRevoke(saved.getId()));

        eventPublisher.publishEvent(new PermissionRevokedEvent(saved.getId(), actorId, actorRole, dualControlApproverId, Map.of(
                "permission", definition.getCode(),
                "grantType", grant.getGrantType().name()
        )));
        return saved;
    }

    // ── Org-admin tier: role delegation (relayed for COMPANY_ADMIN) ──────────

    public PermissionGrant grantToRole(UUID legalEntityId, UUID chainConfigId, String roleCode,
                                       UUID definitionId, UUID actorId, String actorRole) {
        PermissionDefinition definition = requireDefinition(definitionId);
        OrgRegistration org = requireActiveOrgByEntity(legalEntityId, chainConfigId);

        grantRepository.findByPermissionDefinitionIdAndOrgRegistrationIdAndGrantTypeAndStatus(
                        definitionId, org.getId(), PermissionGrantType.ORG, PermissionGrantStatus.ACTIVE)
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "Your organization does not hold '" + definition.getCode() + "'"));

        String normalizedRole = roleCode.trim().toUpperCase();
        grantRepository.findByPermissionDefinitionIdAndOrgRegistrationIdAndGrantTypeAndRoleCodeAndStatus(
                        definitionId, org.getId(), PermissionGrantType.ROLE, normalizedRole, PermissionGrantStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new InvalidStateTransitionException(
                            "Role " + normalizedRole + " already carries '" + definition.getCode() + "'");
                });

        PermissionGrant grant = new PermissionGrant();
        grant.setPermissionDefinitionId(definitionId);
        grant.setOrgRegistrationId(org.getId());
        grant.setGrantType(PermissionGrantType.ROLE);
        grant.setRoleCode(normalizedRole);
        grant.setStatus(PermissionGrantStatus.PENDING);
        PermissionGrant saved = grantRepository.save(grant);
        AfterCommit.run(() -> broadcaster.broadcastGrant(saved.getId()));

        eventPublisher.publishEvent(new PermissionGrantedEvent(saved.getId(), actorId, actorRole, null, Map.of(
                "permission", definition.getCode(),
                "orgRegistrationId", org.getId().toString(),
                "grantType", "ROLE",
                "role", normalizedRole
        )));
        return saved;
    }

    public PermissionGrant setRoleRestricted(UUID legalEntityId, UUID chainConfigId,
                                             UUID definitionId, boolean restricted,
                                             UUID actorId, String actorRole) {
        PermissionDefinition definition = requireDefinition(definitionId);
        OrgRegistration org = legalEntityId != null
                ? requireActiveOrgByEntity(legalEntityId, chainConfigId)
                : null;
        if (org == null) {
            throw new EntityNotFoundException("OrgRegistration", chainConfigId);
        }

        PermissionGrant orgGrant = grantRepository
                .findByPermissionDefinitionIdAndOrgRegistrationIdAndGrantTypeAndStatus(
                        definitionId, org.getId(), PermissionGrantType.ORG, PermissionGrantStatus.ACTIVE)
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "Your organization does not hold '" + definition.getCode() + "'"));

        orgGrant.setRoleRestricted(restricted);
        PermissionGrant saved = grantRepository.save(orgGrant);
        // Broadcast follows the commit; a failed setRoleRestricted surfaces as drift
        // via the chain reconciliation job.
        AfterCommit.run(() -> broadcaster.broadcastRoleRestriction(saved.getId()));

        eventPublisher.publishEvent(new PermissionRoleRestrictionChangedEvent(saved.getId(), actorId, actorRole,
                Map.of("permission", definition.getCode(), "orgRegistrationId", org.getId().toString(),
                        "restricted", restricted)));
        return saved;
    }

    // ── Ecosystem trusted issuers ─────────────────────────────────────────────

    public EcosystemTrustedIssuer addTrustedIssuer(UUID chainConfigId, String issuerAddress,
                                                   List<Long> claimTopics, UUID legalEntityId,
                                                   UUID actorId, String actorRole, UUID dualControlApproverId) {
        if (claimTopics == null || claimTopics.isEmpty()) {
            throw new IllegalArgumentException("At least one claim topic is required");
        }

        txGateway.requireChain(chainConfigId); // fail fast on an unknown chain

        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setChainConfigId(chainConfigId);
        issuer.setIssuerAddress(issuerAddress.toLowerCase());
        issuer.setClaimTopics(claimTopics);
        issuer.setLegalEntityId(legalEntityId);
        issuer.setStatus(MemberWalletStatus.PENDING);
        issuer.setDualControlApproverId(dualControlApproverId);
        issuer.setDualControlApprovedAt(dualControlApproverId != null ? Instant.now() : null);
        EcosystemTrustedIssuer saved = trustedIssuerRepository.save(issuer);
        AfterCommit.run(() -> broadcaster.broadcastAddIssuer(saved.getId()));

        eventPublisher.publishEvent(new TrustedIssuerChangedEvent(saved.getId(), actorId, actorRole, dualControlApproverId, Map.of(
                "action", "ADDED", "issuer", saved.getIssuerAddress(), "topics", claimTopics.toString()
        )));
        return saved;
    }

    public EcosystemTrustedIssuer removeTrustedIssuer(UUID issuerId, UUID actorId, String actorRole,
                                                      UUID dualControlApproverId) {
        EcosystemTrustedIssuer issuer = trustedIssuerRepository.findById(issuerId)
                .orElseThrow(() -> new EntityNotFoundException("EcosystemTrustedIssuer", issuerId));
        if (issuer.getStatus() == MemberWalletStatus.REMOVED) {
            throw new InvalidStateTransitionException("Issuer already removed");
        }

        issuer.setStatus(MemberWalletStatus.REMOVED);
        issuer.setRemovedAt(Instant.now());
        if (dualControlApproverId != null) {
            issuer.setDualControlApproverId(dualControlApproverId);
            issuer.setDualControlApprovedAt(Instant.now());
        }
        EcosystemTrustedIssuer saved = trustedIssuerRepository.save(issuer);
        AfterCommit.run(() -> broadcaster.broadcastRemoveIssuer(saved.getId()));

        eventPublisher.publishEvent(new TrustedIssuerChangedEvent(saved.getId(), actorId, actorRole, dualControlApproverId, Map.of(
                "action", "REMOVED", "issuer", saved.getIssuerAddress()
        )));
        return saved;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public PermissionDefinition requireDefinition(UUID definitionId) {
        return definitionRepository.findById(definitionId)
                .orElseThrow(() -> new EntityNotFoundException("PermissionDefinition", definitionId));
    }

    private OrgRegistration requireActiveOrg(UUID orgRegistrationId) {
        OrgRegistration org = registrationRepository.findById(orgRegistrationId)
                .orElseThrow(() -> new EntityNotFoundException("OrgRegistration", orgRegistrationId));
        if (org.getStatus() != OrgRegistrationStatus.ACTIVE) {
            throw new InvalidStateTransitionException("Org registration is " + org.getStatus() + ", not ACTIVE");
        }
        return org;
    }

    private OrgRegistration requireActiveOrgByEntity(UUID legalEntityId, UUID chainConfigId) {
        OrgRegistration org = registrationRepository
                .findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("OrgRegistration", legalEntityId));
        if (org.getStatus() != OrgRegistrationStatus.ACTIVE) {
            throw new InvalidStateTransitionException("Org registration is " + org.getStatus() + ", not ACTIVE");
        }
        return org;
    }
}
