package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantType;
import de.makibytes.registerwerk.orgidentity.api.RoleRestrictionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Broadcasts ecosystem chain transactions <em>after</em> the intent has been committed
 * to the database (see {@link de.makibytes.registerwerk.shared.AfterCommit}): the
 * committed row (PENDING/REVOCATION_PENDING/REMOVAL_PENDING with a null tx hash) is the source of intent,
 * this component turns it into a chain transaction and records the hash in a fresh
 * transaction. Every method is idempotent — it re-checks state and skips rows that
 * already carry a tx hash — so the {@link OrgEcosystemTxPoller} can safely retry rows
 * whose broadcast failed (RPC down, node hiccup). Failures are logged, never thrown:
 * the missing tx hash keeps the row eligible for the next retry.
 *
 * <p>Every submitted transition stores its transaction hash before this transaction commits.
 * The receipt poller is the sole writer of terminal chain-derived state.
 */
@Component
public class EcosystemOnchainBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EcosystemOnchainBroadcaster.class);

    private final OrgRegistrationRepository registrationRepository;
    private final OrgMemberWalletRepository walletRepository;
    private final PermissionGrantRepository grantRepository;
    private final PermissionDefinitionRepository definitionRepository;
    private final EcosystemTrustedIssuerRepository trustedIssuerRepository;
    private final EcosystemTxGateway txGateway;

    EcosystemOnchainBroadcaster(
            OrgRegistrationRepository registrationRepository,
            OrgMemberWalletRepository walletRepository,
            PermissionGrantRepository grantRepository,
            PermissionDefinitionRepository definitionRepository,
            EcosystemTrustedIssuerRepository trustedIssuerRepository,
            EcosystemTxGateway txGateway) {
        this.registrationRepository = registrationRepository;
        this.walletRepository = walletRepository;
        this.grantRepository = grantRepository;
        this.definitionRepository = definitionRepository;
        this.trustedIssuerRepository = trustedIssuerRepository;
        this.txGateway = txGateway;
    }

    /** Submits {@code OrgRegistry.registerOrg} for a committed PENDING registration. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastRegisterOrg(UUID registrationId) {
        registrationRepository.findById(registrationId).ifPresent(registration -> {
            if (registration.getStatus() != OrgRegistrationStatus.PENDING
                    || registration.getRegisteredTx() != null
                    || registration.getOrgAddress().startsWith(OrgRegistrationService.PENDING_IDENTITY_PREFIX)) {
                return;
            }
            try {
                short country = registration.getCountryCode() != null ? registration.getCountryCode() : 0;
                String txHash = txGateway.submitToOrgRegistry(registration.getChainConfigId(),
                        EcosystemAbi.registerOrg(registration.getOrgAddress(), country),
                        Map.of("orgAddress", registration.getOrgAddress(), "country", String.valueOf(country)));
                registration.setRegisteredTx(txHash);
                registrationRepository.save(registration);
            } catch (Exception e) {
                log.warn("registerOrg broadcast failed for registration={} (poller will retry): {}",
                        registrationId, e.getMessage());
            }
        });
    }

    /** Submits a committed suspend/reinstate intent exactly once per stored attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastOrgStatus(UUID registrationId, String reason) {
        registrationRepository.findById(registrationId).ifPresent(registration -> {
            if ((registration.getStatus() != OrgRegistrationStatus.SUSPEND_PENDING
                    && registration.getStatus() != OrgRegistrationStatus.REINSTATE_PENDING)
                    || registration.getStatusTx() != null) {
                return;
            }
            try {
                String txHash;
                if (registration.getStatus() == OrgRegistrationStatus.SUSPEND_PENDING) {
                    txHash = txGateway.submitToOrgRegistry(registration.getChainConfigId(),
                            EcosystemAbi.suspendOrg(registration.getOrgAddress(), reason != null ? reason : ""),
                            Map.of("orgAddress", registration.getOrgAddress(),
                                    "reason", reason != null ? reason : ""));
                } else {
                    txHash = txGateway.submitToOrgRegistry(registration.getChainConfigId(),
                            EcosystemAbi.reinstateOrg(registration.getOrgAddress()),
                            Map.of("orgAddress", registration.getOrgAddress()));
                }
                registration.setStatusTx(txHash);
                registrationRepository.save(registration);
            } catch (Exception e) {
                log.warn("Org status broadcast failed for registration={} (poller will retry): {}",
                        registrationId, e.getMessage());
            }
        });
    }

    /** Submits {@code OrgRegistry.addMember} for a committed PENDING wallet binding. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastAddMember(UUID walletId) {
        walletRepository.findById(walletId).ifPresent(wallet -> {
            if (wallet.getStatus() != MemberWalletStatus.PENDING || wallet.getBoundTx() != null) {
                return;
            }
            OrgRegistration registration = registrationRepository
                    .findById(wallet.getOrgRegistrationId()).orElse(null);
            if (registration == null) {
                return;
            }
            try {
                String txHash = txGateway.submitToOrgRegistry(wallet.getChainConfigId(),
                        EcosystemAbi.addMember(registration.getOrgAddress(), wallet.getWalletAddress(),
                                wallet.getRoles()),
                        Map.of("orgAddress", registration.getOrgAddress(),
                                "wallet", wallet.getWalletAddress(),
                                "roles", String.join(",", wallet.getRoles())));
                wallet.setBoundTx(txHash);
                walletRepository.save(wallet);
            } catch (Exception e) {
                log.warn("addMember broadcast failed for wallet={} (poller will retry): {}",
                        walletId, e.getMessage());
            }
        });
    }

    /** Submits a committed fail-closed member-removal intent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastRemoveMember(UUID walletId) {
        walletRepository.findById(walletId).ifPresent(wallet -> {
            if (wallet.getStatus() != MemberWalletStatus.REMOVAL_PENDING
                    || wallet.getRemovedTx() != null) {
                return;
            }
            OrgRegistration registration = registrationRepository
                    .findById(wallet.getOrgRegistrationId()).orElse(null);
            if (registration == null) {
                return;
            }
            try {
                String txHash = txGateway.submitToOrgRegistry(wallet.getChainConfigId(),
                        EcosystemAbi.removeMember(registration.getOrgAddress(), wallet.getWalletAddress()),
                        Map.of("orgAddress", registration.getOrgAddress(), "wallet", wallet.getWalletAddress()));
                wallet.setRemovedTx(txHash);
                walletRepository.save(wallet);
            } catch (Exception e) {
                log.warn("removeMember broadcast failed for wallet={} (poller will retry): {}",
                        walletId, e.getMessage());
            }
        });
    }

    /** Submits the grant call for a committed PENDING permission grant (org or role tier). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastGrant(UUID grantId) {
        grantRepository.findById(grantId).ifPresent(grant -> {
            if (grant.getStatus() != PermissionGrantStatus.PENDING || grant.getGrantedTx() != null) {
                return;
            }
            GrantContext context = grantContext(grant);
            if (context == null) {
                return;
            }
            try {
                String txHash = grant.getGrantType() == PermissionGrantType.ORG
                        ? txGateway.submitToPermissionRegistry(context.org().getChainConfigId(),
                                EcosystemAbi.grantToOrg(context.org().getOrgAddress(), context.code()),
                                Map.of("orgAddress", context.org().getOrgAddress(), "permission", context.code()))
                        : txGateway.submitToPermissionRegistry(context.org().getChainConfigId(),
                                EcosystemAbi.grantToRole(context.org().getOrgAddress(), grant.getRoleCode(),
                                        context.code()),
                                Map.of("orgAddress", context.org().getOrgAddress(), "permission", context.code(),
                                        "role", grant.getRoleCode()));
                grant.setGrantedTx(txHash);
                grantRepository.save(grant);
            } catch (Exception e) {
                log.warn("Grant broadcast failed for grant={} (poller will retry): {}", grantId, e.getMessage());
            }
        });
    }

    /** Submits the revoke call for a committed fail-closed revocation intent without a tx yet. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastRevoke(UUID grantId) {
        grantRepository.findById(grantId).ifPresent(grant -> {
            if (grant.getStatus() != PermissionGrantStatus.REVOCATION_PENDING || grant.getRevokedTx() != null) {
                return;
            }
            GrantContext context = grantContext(grant);
            if (context == null) {
                return;
            }
            try {
                String txHash = grant.getGrantType() == PermissionGrantType.ORG
                        ? txGateway.submitToPermissionRegistry(context.org().getChainConfigId(),
                                EcosystemAbi.revokeFromOrg(context.org().getOrgAddress(), context.code()),
                                Map.of("orgAddress", context.org().getOrgAddress(), "permission", context.code()))
                        : txGateway.submitToPermissionRegistry(context.org().getChainConfigId(),
                                EcosystemAbi.revokeFromRole(context.org().getOrgAddress(), grant.getRoleCode(),
                                        context.code()),
                                Map.of("orgAddress", context.org().getOrgAddress(), "permission", context.code(),
                                        "role", grant.getRoleCode()));
                grant.setRevokedTx(txHash);
                grantRepository.save(grant);
            } catch (Exception e) {
                log.warn("Revoke broadcast failed for grant={} (poller will retry): {}", grantId, e.getMessage());
            }
        });
    }

    /** Submits a committed fail-closed role-restriction change. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastRoleRestriction(UUID grantId) {
        grantRepository.findById(grantId).ifPresent(grant -> {
            if (grant.getRoleRestrictionStatus() != RoleRestrictionStatus.CHANGE_PENDING
                    || grant.getRequestedRoleRestricted() == null
                    || grant.getRoleRestrictionTx() != null) {
                return;
            }
            GrantContext context = grantContext(grant);
            if (context == null) {
                return;
            }
            try {
                String txHash = txGateway.submitToPermissionRegistry(context.org().getChainConfigId(),
                        EcosystemAbi.setRoleRestricted(context.org().getOrgAddress(), context.code(),
                                grant.getRequestedRoleRestricted()),
                        Map.of("orgAddress", context.org().getOrgAddress(), "permission", context.code(),
                                "restricted", String.valueOf(grant.getRequestedRoleRestricted())));
                grant.setRoleRestrictionTx(txHash);
                grantRepository.save(grant);
            } catch (Exception e) {
                log.warn("Role-restriction broadcast failed for grant={} (poller will retry): {}",
                        grantId, e.getMessage());
            }
        });
    }

    /** Submits {@code addTrustedIssuer} for a committed PENDING issuer. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastAddIssuer(UUID issuerId) {
        trustedIssuerRepository.findById(issuerId).ifPresent(issuer -> {
            if (issuer.getStatus() != TrustedIssuerStatus.PENDING || issuer.getAddedTx() != null) {
                return;
            }
            try {
                String txHash = txGateway.submitToEcosystemTir(issuer.getChainConfigId(),
                        EcosystemAbi.addTrustedIssuer(issuer.getIssuerAddress(), issuer.getClaimTopics()),
                        Map.of("issuer", issuer.getIssuerAddress(), "topics", issuer.getClaimTopics().toString()));
                issuer.setAddedTx(txHash);
                trustedIssuerRepository.save(issuer);
            } catch (Exception e) {
                log.warn("addTrustedIssuer broadcast failed for issuer={} (poller will retry): {}",
                        issuerId, e.getMessage());
            }
        });
    }

    /** Submits {@code removeTrustedIssuer} for a committed fail-closed removal intent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void broadcastRemoveIssuer(UUID issuerId) {
        trustedIssuerRepository.findById(issuerId).ifPresent(issuer -> {
            if (issuer.getStatus() != TrustedIssuerStatus.REMOVAL_PENDING || issuer.getRemovedTx() != null) {
                return;
            }
            try {
                String txHash = txGateway.submitToEcosystemTir(issuer.getChainConfigId(),
                        EcosystemAbi.removeTrustedIssuer(issuer.getIssuerAddress()),
                        Map.of("issuer", issuer.getIssuerAddress()));
                issuer.setRemovedTx(txHash);
                trustedIssuerRepository.save(issuer);
            } catch (Exception e) {
                log.warn("removeTrustedIssuer broadcast failed for issuer={} (poller will retry): {}",
                        issuerId, e.getMessage());
            }
        });
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private record GrantContext(OrgRegistration org, String code) {}

    private GrantContext grantContext(PermissionGrant grant) {
        PermissionDefinition definition = definitionRepository
                .findById(grant.getPermissionDefinitionId()).orElse(null);
        OrgRegistration org = registrationRepository.findById(grant.getOrgRegistrationId()).orElse(null);
        if (definition == null || org == null) {
            log.warn("Grant {} references a missing definition or org; skipping broadcast", grant.getId());
            return null;
        }
        return new GrantContext(org, definition.getCode());
    }
}
