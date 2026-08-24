package de.makibytes.registerwerk.erc3643.api;

import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link OnchainClaim} entities.
 */
public interface OnchainClaimRepository extends JpaRepository<OnchainClaim, UUID> {

    /**
     * Returns all claims issued to a specific ONCHAINID identity.
     */
    List<OnchainClaim> findByOnchainIdentityId(UUID onchainIdentityId);

    /**
     * Returns all claims of a specific topic issued to a specific ONCHAINID identity.
     * Useful for checking whether a required claim topic is satisfied.
     */
    List<OnchainClaim> findByOnchainIdentityIdAndTopic(UUID onchainIdentityId, long topic);

    Optional<OnchainClaim> findByIdAndOnchainIdentityId(UUID id, UUID onchainIdentityId);

    /** Claims with a submitted {@code addClaim} tx not yet resolved — scoped so this shrinks over
     *  time instead of re-scanning every claim ever issued (see
     *  {@code Erc3643ClaimConfirmationListener}). */
    List<OnchainClaim> findByTxHashIsNotNullAndConfirmedFalse();

    /** Claims with a submitted {@code removeClaim} tx not yet resolved — {@code revokedAt} being
     *  null is itself the "not yet confirmed" signal (see {@code Erc3643ClaimConfirmationListener}). */
    List<OnchainClaim> findByRevocationTxHashIsNotNullAndRevokedAtIsNull();
}
