package de.makibytes.registerwerk.blockchain.api;

import java.math.BigInteger;
import java.util.UUID;

/**
 * Cross-module surface onto {@code blockchain.internal.TokenAdminService.forceBurn} /
 * {@code forceBurnSingle} — lets {@code asset.internal.AssetRedemptionListener} dispatch
 * on-chain burns when an asset redeems, without importing {@code blockchain.internal} (which
 * would violate the Modulith boundary; the reverse edge, {@code asset -> blockchain.api},
 * already exists safely via {@code AssetDeploymentService}, so this reuses rather than inverts it).
 *
 * <p>Covers only ERC-20/721/1155 (what {@code TokenAdminService} itself handles) — ERC-3643 and
 * every non-EVM standard remain manual-operator redemptions via their existing endpoints
 * ({@code Erc3643Controller}, the Solana/Starknet/Stellar admin services), same as before.
 */
public interface TokenAdminPort {

    /**
     * @return a blockchain-transaction tracking UUID (poll {@code GET /api/v1/transactions/{txId}})
     */
    UUID forceBurn(UUID deploymentId, String from, BigInteger value, String legalBasis,
                    UUID actorId, String actorRole);

    /**
     * ERC-1155 only: forces burn of {@code amount} of token {@code id} from {@code from}. Since
     * {@link #forceBurn} now rejects ERC-1155 outright (it can only ever target token id 0 —
     * never the real tranche a caller may mean), {@code AssetRedemptionListener} calls this
     * instead, explicitly passing id 0, to keep full-redemption burns working exactly as before
     * for ERC-1155 ({@code AssetHolder} does not yet track per-id balances).
     *
     * @return a blockchain-transaction tracking UUID (poll {@code GET /api/v1/transactions/{txId}})
     */
    UUID forceBurnSingle(UUID deploymentId, String from, BigInteger id, BigInteger amount,
                          String legalBasis, UUID actorId, String actorRole);

    /**
     * Mints {@code amount} whole units to {@code toAddress} (ERC-20 family). Used by the primary
     * subscription settlement (T3-08) when the subscribed asset has a confirmed deployment: the
     * indexer sync then credits the register from the transfer.
     *
     * @return a blockchain-transaction tracking UUID (poll {@code GET /api/v1/transactions/{txId}})
     */
    UUID mint(UUID deploymentId, String toAddress, BigInteger amount, UUID actorId, String actorRole);

    /**
     * Freezes {@code walletAddress} on the given (non-ERC-3643) token deployment — AWG §17,
     * GwG §40; MiCAR Art. 36. Exposed here so {@code erc3643.internal.SperrvermerkOnchainSyncListener}
     * can keep a §16 eWpG Sperrvermerk in sync with the on-chain frozen flag without importing
     * {@code blockchain.internal} (would violate the Modulith boundary — same rationale as
     * {@link #forceBurn} above).
     */
    UUID freezeAddress(UUID deploymentId, String walletAddress, String reason, String legalBasis,
                        UUID actorId, String actorRole);

    /**
     * Lifts a freeze applied via {@link #freezeAddress} after a §16 eWpG Sperrvermerk was lifted.
     * For {@code SperrvermerkOnchainSyncListener} only: always runs as the SYSTEM actor and skips
     * the Sperrvermerk check that the manual {@code unfreezeAddress} applies (T3-16) — the
     * listener itself verifies that no other ACTIVE block still covers the wallet.
     */
    UUID unfreezeAfterBlockLift(UUID deploymentId, String walletAddress);
}
