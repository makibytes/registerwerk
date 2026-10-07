package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** Public port for ERC-7540 async vault admin operations. Used by asset/web/VaultController.
 *  Fulfilment always settles at the NAV currently struck on-chain; the executed NAV is recorded
 *  from the fulfilment event once confirmed, never supplied by the caller. */
public interface Erc7540AdminPort {
    UUID fulfillDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID fulfillRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID cancelDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID cancelRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID fulfillRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    UUID cancelRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole);
    /** Registry force-cancel on a legal basis: moves the request's escrow to {@code toAddress}. */
    UUID forceCancelRequest(UUID deploymentId, BigInteger onChainRequestId, String toAddress, String legalBasis,
                            UUID actorId, String actorRole);
    /**
     * Registry {@code setDealingCutoff(cutoffSecondsOfDay, periodSecs)} on an existing vault (T1-07 forward
     * pricing). Only affects requests placed after it confirms — existing dealing points never move.
     * The controller layer requires step-up + a second approver.
     */
    UUID setDealingCutoff(UUID deploymentId, int cutoffSecondsOfDay, long periodSeconds,
                          UUID actorId, String actorRole);
    /**
     * After a vault deployment is confirmed: sends the configured default dealing cut-off
     * ({@code registerwerk.vault.dealing-cutoff-utc} / {@code dealing-period-seconds}) as the registry signer,
     * unless the vault already has one. Never throws — a vault left unconfigured is refused in production mode
     * and listed by the readiness warning; the operator can then set it explicitly.
     */
    void configureDealingCutoffAfterDeployment(UUID deploymentId);
    /** Live on-chain dealing cut-off state of the vault ({@code applicable=false} for other standards). */
    VaultDealingState dealingState(UUID deploymentId);
    /**
     * Production mode only (no-op otherwise): fails with a 409-mapped exception when a confirmed ERC-7540
     * vault of {@code assetId} has no dealing cut-off configured on-chain, or when that cannot be read (fail
     * closed) — such a vault would price new subscriptions at an already-known NAV.
     */
    void requireDealingCutoffConfigured(UUID assetId, String action);
    /** Confirmed ERC-7540 vaults without a (readable) on-chain dealing cut-off, as operator-readable lines. */
    List<String> listVaultsWithoutDealingCutoff();
    List<VaultRequest> listRequests(UUID assetId, VaultRequestStatus status);
    /** Requests of the deployment's asset in {@code status}, with the current compliance hold
     *  evaluated for PENDING ones. */
    List<VaultRequestView> listRequestViews(UUID deploymentId, VaultRequestStatus status);
}
