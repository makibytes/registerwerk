package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * Operator view of one ERC-7540 request: the {@code vault_request} row plus whether a compliance
 * hold currently blocks fulfil/cancel on it.
 *
 * @param awaitingConfirmation a fulfil/cancel tx was submitted and is not yet final
 * @param complianceHold       the owner (fulfil recipient) or the refund recipient is frozen on
 *                             the vault or under a §16 eWpG Sperrvermerk — fulfil/cancel are refused;
 *                             only a registry force-cancel on a legal basis can move the escrow
 * @param complianceHoldReason human-readable reason when {@code complianceHold}
 * @param dealingPoint         forward pricing (T1-07): the instant from which a NAV struck on-chain may settle this
 *                             request (its "dealing day"); null for legacy requests placed before the vault had a
 *                             dealing cut-off, for settled requests and when the read failed
 * @param awaitingNavStrike    a dealing point exists and the latest NAV was struck before it — fulfilment is
 *                             refused until the next NAV strike (not a failure)
 */
public record VaultRequestView(
        UUID id,
        UUID assetId,
        BigInteger requestId,
        VaultRequestType requestType,
        String controllerAddr,
        String ownerAddr,
        String payerAddr,
        BigInteger assetAmount,
        BigInteger shareAmount,
        VaultRequestStatus requestStatus,
        Instant requestedAt,
        Instant fulfilledAt,
        String fulfilledTx,
        String cancelledTx,
        BigDecimal navAtFulfill,
        String forcedToAddr,
        String legalBasis,
        String reviewNote,
        boolean awaitingConfirmation,
        boolean complianceHold,
        String complianceHoldReason,
        Instant dealingPoint,
        boolean awaitingNavStrike) {

    public static VaultRequestView of(VaultRequest r, boolean complianceHold, String complianceHoldReason) {
        return new VaultRequestView(
                r.getId(), r.getAssetId(), r.getRequestId(), r.getRequestType(),
                r.getControllerAddr(), r.getOwnerAddr(), r.getPayerAddr(),
                r.getAssetAmount(), r.getShareAmount(), r.getRequestStatus(),
                r.getRequestedAt(), r.getFulfilledAt(), r.getFulfilledTx(), r.getCancelledTx(),
                r.getNavAtFulfill(), r.getForcedToAddr(), r.getLegalBasis(), r.getReviewNote(),
                !r.isConfirmed() && (r.getFulfilledTx() != null || r.getCancelledTx() != null),
                complianceHold, complianceHoldReason, null, false);
    }

    /** This view with the forward-pricing fields filled in; {@code navStruckAt} null means "no NAV struck yet". */
    public VaultRequestView withDealing(Instant dealingPoint, Instant navStruckAt) {
        boolean awaiting = dealingPoint != null && (navStruckAt == null || navStruckAt.isBefore(dealingPoint));
        return new VaultRequestView(id, assetId, requestId, requestType, controllerAddr, ownerAddr, payerAddr,
                assetAmount, shareAmount, requestStatus, requestedAt, fulfilledAt, fulfilledTx, cancelledTx,
                navAtFulfill, forcedToAddr, legalBasis, reviewNote, awaitingConfirmation, complianceHold,
                complianceHoldReason, dealingPoint, awaiting);
    }
}
