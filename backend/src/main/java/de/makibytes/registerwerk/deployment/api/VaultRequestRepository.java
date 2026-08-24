package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VaultRequestRepository extends JpaRepository<VaultRequest, UUID> {

    List<VaultRequest> findByAssetIdAndRequestStatus(UUID assetId, VaultRequestStatus status);

    Optional<VaultRequest> findByAssetIdAndRequestId(UUID assetId, java.math.BigInteger requestId);

    /** Requests with a submitted fulfil/cancel tx not yet resolved — scoped so each query shrinks
     *  over time instead of re-scanning every request ever made (see
     *  {@code VaultConfirmationListener}). Mutually exclusive per row in practice (a request can
     *  only ever be fulfilled or cancelled once), but queried separately since the listener needs
     *  to know which action to apply on confirmation. */
    List<VaultRequest> findByFulfilledTxIsNotNullAndConfirmedFalse();

    List<VaultRequest> findByCancelledTxIsNotNullAndConfirmedFalse();
}
