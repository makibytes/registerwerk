package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VaultRequestRepository extends JpaRepository<VaultRequest, UUID> {

    List<VaultRequest> findByAssetIdAndRequestStatus(UUID assetId, VaultRequestStatus status);

    /** Request ids are per vault contract, so the key is (asset, chain, request id): the same asset
     *  can be deployed on several chains and each vault counts its own requests from 1. */
    Optional<VaultRequest> findByAssetIdAndChainConfigIdAndRequestId(
            UUID assetId, UUID chainConfigId, java.math.BigInteger requestId);

    /** Legacy/demo rows whose chain was never recorded (V18 could not derive it); a chain-aware
     *  lookup falls back to these and attaches the chain on first touch. */
    Optional<VaultRequest> findByAssetIdAndChainConfigIdIsNullAndRequestId(
            UUID assetId, java.math.BigInteger requestId);

    /** Requests with a submitted fulfil/cancel tx not yet resolved — scoped so each query shrinks
     *  over time instead of re-scanning every request ever made (see
     *  {@code VaultConfirmationListener}). Mutually exclusive per row in practice (a request can
     *  only ever be fulfilled or cancelled once), but queried separately since the listener needs
     *  to know which action to apply on confirmation. */
    List<VaultRequest> findByFulfilledTxIsNotNullAndConfirmedFalse();

    List<VaultRequest> findByCancelledTxIsNotNullAndConfirmedFalse();
}
