package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.WalletBindChallenge;
import de.makibytes.registerwerk.orgidentity.api.WalletBindChallengeRepository;
import de.makibytes.registerwerk.orgidentity.api.WalletSignatureVerifier;
import de.makibytes.registerwerk.orgidentity.events.MemberWalletBoundEvent;
import de.makibytes.registerwerk.orgidentity.events.MemberWalletRemovedEvent;
import de.makibytes.registerwerk.shared.AfterCommit;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Binds member wallets to an organization. Control of the wallet is proven with an
 * EIP-191 {@code personal_sign} over a server-issued nonce challenge before the
 * {@code OrgRegistry.addMember} transaction is relayed by the operator wallet. Signature
 * verification (EOA or smart-contract wallet) is delegated to {@link WalletSignatureVerifier}.
 */
@Service
@Transactional
public class MemberWalletService {

    private static final Logger log = LoggerFactory.getLogger(MemberWalletService.class);
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
    private static final int MAX_ROLES = 16;

    private final OrgMemberWalletRepository walletRepository;
    private final WalletBindChallengeRepository challengeRepository;
    private final OrgRegistrationRepository registrationRepository;
    private final EcosystemOnchainBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;
    private final WalletSignatureVerifier signatureVerifier;
    private final SecureRandom secureRandom = new SecureRandom();

    MemberWalletService(
            OrgMemberWalletRepository walletRepository,
            WalletBindChallengeRepository challengeRepository,
            OrgRegistrationRepository registrationRepository,
            EcosystemOnchainBroadcaster broadcaster,
            ApplicationEventPublisher eventPublisher,
            WalletSignatureVerifier signatureVerifier) {
        this.walletRepository = walletRepository;
        this.challengeRepository = challengeRepository;
        this.registrationRepository = registrationRepository;
        this.broadcaster = broadcaster;
        this.eventPublisher = eventPublisher;
        this.signatureVerifier = signatureVerifier;
    }

    /** The exact text the wallet owner signs with personal_sign. */
    public static String challengeMessage(UUID legalEntityId, UUID chainConfigId,
                                          String walletAddress, String nonce) {
        return "Registerwerk wallet binding\n"
                + "entity: " + legalEntityId + "\n"
                + "chain: " + chainConfigId + "\n"
                + "wallet: " + walletAddress.toLowerCase() + "\n"
                + "nonce: " + nonce;
    }

    /** Issues a fresh nonce challenge for binding {@code walletAddress} to the entity's org. */
    public WalletBindChallenge createChallenge(UUID legalEntityId, UUID chainConfigId, String walletAddress) {
        requireActiveOrg(legalEntityId, chainConfigId);
        requireUnboundWallet(chainConfigId, walletAddress);

        byte[] nonceBytes = new byte[16];
        secureRandom.nextBytes(nonceBytes);

        WalletBindChallenge challenge = new WalletBindChallenge();
        challenge.setLegalEntityId(legalEntityId);
        challenge.setChainConfigId(chainConfigId);
        challenge.setWalletAddress(walletAddress.toLowerCase());
        challenge.setNonce(HexFormat.of().formatHex(nonceBytes));
        challenge.setExpiresAt(Instant.now().plus(CHALLENGE_TTL));
        return challengeRepository.save(challenge);
    }

    /**
     * Verifies the challenge signature and relays {@code addMember} via the operator wallet.
     * The binding stays PENDING until the transaction confirms.
     */
    public OrgMemberWallet bindWallet(UUID legalEntityId, UUID chainConfigId, String walletAddress,
                                      String signature, List<String> roles, String label,
                                      UUID appUserId, String actorRole) {
        if (roles != null && roles.size() > MAX_ROLES) {
            throw new IllegalArgumentException("At most " + MAX_ROLES + " roles per wallet");
        }
        OrgRegistration registration = requireActiveOrg(legalEntityId, chainConfigId);
        requireUnboundWallet(chainConfigId, walletAddress);

        WalletBindChallenge challenge = challengeRepository
                .findOpenChallenge(legalEntityId, chainConfigId, walletAddress)
                .orElseThrow(() -> new EntityNotFoundException("WalletBindChallenge", legalEntityId));
        if (challenge.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidStateTransitionException("Wallet binding challenge expired; request a new one");
        }

        String message = challengeMessage(legalEntityId, chainConfigId, walletAddress, challenge.getNonce());
        signatureVerifier.verifyPersonalSign(chainConfigId, message, signature, walletAddress);

        challenge.setUsedAt(Instant.now());
        challengeRepository.save(challenge);

        List<String> effectiveRoles = roles != null ? roles : List.of();
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setOrgRegistrationId(registration.getId());
        wallet.setChainConfigId(chainConfigId);
        wallet.setWalletAddress(walletAddress.toLowerCase());
        wallet.setAppUserId(appUserId);
        wallet.setLabel(label);
        wallet.setRoles(effectiveRoles);
        wallet.setStatus(MemberWalletStatus.PENDING);
        OrgMemberWallet saved = walletRepository.save(wallet);
        // addMember is relayed only after the PENDING row commits; the poller retries
        // committed rows whose broadcast failed.
        AfterCommit.run(() -> broadcaster.broadcastAddMember(saved.getId()));

        eventPublisher.publishEvent(new MemberWalletBoundEvent(saved.getId(), appUserId, actorRole, Map.of(
                "wallet", saved.getWalletAddress(),
                "orgRegistrationId", registration.getId().toString(),
                "roles", String.join(",", effectiveRoles)
        )));
        return saved;
    }

    /** Unbinds a wallet via {@code removeMember}; the row is marked REMOVED immediately. */
    public OrgMemberWallet removeWallet(UUID memberWalletId, UUID expectedEntityId,
                                        UUID actorId, String actorRole) {
        OrgMemberWallet wallet = walletRepository.findById(memberWalletId)
                .orElseThrow(() -> new EntityNotFoundException("OrgMemberWallet", memberWalletId));
        OrgRegistration registration = registrationRepository.findById(wallet.getOrgRegistrationId())
                .orElseThrow(() -> new EntityNotFoundException("OrgRegistration", wallet.getOrgRegistrationId()));

        if (expectedEntityId != null && !registration.getLegalEntityId().equals(expectedEntityId)) {
            throw new EntityNotFoundException("OrgMemberWallet", memberWalletId);
        }
        if (wallet.getStatus() == MemberWalletStatus.REMOVED) {
            throw new InvalidStateTransitionException("Wallet binding already removed");
        }

        wallet.setStatus(MemberWalletStatus.REMOVED);
        wallet.setRemovedAt(Instant.now());
        OrgMemberWallet saved = walletRepository.save(wallet);
        // Broadcast follows the commit; a failed removeMember surfaces as drift via the
        // chain reconciliation job.
        AfterCommit.run(() -> broadcaster.broadcastRemoveMember(saved.getId()));

        eventPublisher.publishEvent(new MemberWalletRemovedEvent(saved.getId(), actorId, actorRole, Map.of(
                "wallet", saved.getWalletAddress(),
                "orgRegistrationId", registration.getId().toString()
        )));
        return saved;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrgRegistration requireActiveOrg(UUID legalEntityId, UUID chainConfigId) {
        OrgRegistration registration = registrationRepository
                .findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("OrgRegistration", legalEntityId));
        if (registration.getStatus() != OrgRegistrationStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "Org registration is " + registration.getStatus() + "; wallets can only be bound to an ACTIVE org");
        }
        return registration;
    }

    private void requireUnboundWallet(UUID chainConfigId, String walletAddress) {
        walletRepository.findLiveBinding(chainConfigId, walletAddress).ifPresent(existing -> {
            throw new InvalidStateTransitionException(
                    "Wallet " + walletAddress + " is already bound to an organization on this chain");
        });
    }

}
