package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.chain.api.Network;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API contract for Canton (DAML) token operations.
 * Decouples callers from the DAML-backed {@code CantonTokenService} so they compile
 * without the {@code canton} Maven profile.
 *
 * <p>When the {@code canton} profile is not active a no-op stub bean is registered
 * by {@code CantonTokenDisabledStub}; all methods throw {@link UnsupportedOperationException}.
 *
 * <p>Every method takes the acting operator's {@code actorId}/{@code actorRole} so the
 * implementation can attribute its audit event to a real actor instead of leaving it null.
 */
public interface CantonTokenOperations {

    CompletableFuture<String> createInstrument(
            UUID assetId, Network network, String issuerPartyId, int decimals, UUID actorId, String actorRole);

    CompletableFuture<String> issue(
            UUID deploymentId, String recipientPartyId, BigDecimal amount, UUID actorId, String actorRole);

    CompletableFuture<String> transfer(
            UUID deploymentId, String holdingContractId,
            String fromParty, String toParty, BigDecimal amount, UUID actorId, String actorRole);

    CompletableFuture<String> forceTransfer(
            UUID deploymentId, String holdingContractId,
            String toParty, BigDecimal amount, String reason, UUID actorId, String actorRole);

    CompletableFuture<String> freezeHolding(UUID deploymentId, String holdingContractId, UUID actorId, String actorRole);

    CompletableFuture<String> unfreezeHolding(UUID deploymentId, String holdingContractId, UUID actorId, String actorRole);

    CompletableFuture<String> burn(UUID deploymentId, String holdingContractId, BigDecimal amount, UUID actorId, String actorRole);

    CompletableFuture<String> pauseInstrument(UUID deploymentId, UUID actorId, String actorRole);

    CompletableFuture<String> unpauseInstrument(UUID deploymentId, UUID actorId, String actorRole);
}
