package de.makibytes.registerwerk.erc3643.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Off-chain mirror of the on-chain {@code IdentityRegistry} contract.
 * Maps a wallet address to an ONCHAINID contract within a specific T-REX suite.
 *
 * <p>The on-chain IdentityRegistry stores the canonical mapping; this table allows
 * the backend to answer "is wallet X registered for token Y?" without an RPC call.</p>
 *
 * <p>Fail-closed via {@code removedAt}: a non-null value means a {@code deleteIdentity()}
 * removal is pending or confirmed, so the investor must not be treated as registered. A reorg
 * clears only the removal confirmation provenance; only a confirmed failed removal receipt
 * reactivates the entry.</p>
 */
@Entity
@Table(name = "erc3643_identity_registry",
        uniqueConstraints = @UniqueConstraint(columnNames = {"suite_id", "wallet_address"}))
public class Erc3643IdentityRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    @Column(name = "wallet_address", nullable = false, length = 66)
    private String walletAddress;

    @Column(name = "onchain_identity_id", nullable = false)
    private UUID onchainIdentityId;

    /** ISO-3166-1 numeric country code of the investor. Used for country-restriction checks. */
    @Column(name = "country_code")
    private Short countryCode;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt = Instant.now();

    /** Transaction hash of the on-chain {@code registerIdentity()} call. */
    @Column(name = "registered_by_tx", length = 66)
    private String registeredByTx;

    /** Non-null while removal is pending or confirmed; null only while the mirror may safely
     *  treat the investor as active. */
    @Column(name = "removed_at")
    private Instant removedAt;

    /** Transaction hash of the on-chain {@code deleteIdentity()} call, if any. */
    @Column(name = "removed_by_tx", length = 66)
    private String removedByTx;

    /** Which chain this suite (and therefore this entry) lives on — lets the reorg-retraction
     *  sweep find affected rows by (chainConfigId, blockNumber). Set at registration time; reused
     *  for the removal path too (a wallet's registry entry does not move chains). */
    @Column(name = "chain_config_id")
    private UUID chainConfigId;

    /** True once {@link Erc3643IdentityRegistryConfirmationListener} has journalled (or given up
     *  on) the {@code registered_by_tx} outcome — scopes that listener's polling query so it
     *  shrinks over time instead of re-scanning every registration ever made. */
    @Column(name = "registration_confirmed", nullable = false)
    private boolean registrationConfirmed = false;

    @Column(name = "registration_block_number")
    private Long registrationBlockNumber;

    @Column(name = "registration_block_hash", length = 128)
    private String registrationBlockHash;

    /** True once {@code removed_by_tx} has a final canonical outcome. A reorg resets this to false
     *  while retaining {@link #removedAt} so the unresolved removal stays fail-closed. */
    @Column(name = "removal_confirmed", nullable = false)
    private boolean removalConfirmed = false;

    @Column(name = "removal_block_number")
    private Long removalBlockNumber;

    @Column(name = "removal_block_hash", length = 128)
    private String removalBlockHash;

    public UUID getId() { return id; }
    public UUID getSuiteId() { return suiteId; }
    public void setSuiteId(UUID suiteId) { this.suiteId = suiteId; }
    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }
    public UUID getOnchainIdentityId() { return onchainIdentityId; }
    public void setOnchainIdentityId(UUID onchainIdentityId) { this.onchainIdentityId = onchainIdentityId; }
    public Short getCountryCode() { return countryCode; }
    public void setCountryCode(Short countryCode) { this.countryCode = countryCode; }
    public Instant getRegisteredAt() { return registeredAt; }
    public String getRegisteredByTx() { return registeredByTx; }
    public void setRegisteredByTx(String registeredByTx) { this.registeredByTx = registeredByTx; }
    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
    public boolean isActive() { return removedAt == null; }

    public String getRemovedByTx() { return removedByTx; }
    public void setRemovedByTx(String removedByTx) { this.removedByTx = removedByTx; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public boolean isRegistrationConfirmed() { return registrationConfirmed; }
    public void setRegistrationConfirmed(boolean registrationConfirmed) { this.registrationConfirmed = registrationConfirmed; }

    public Long getRegistrationBlockNumber() { return registrationBlockNumber; }
    public void setRegistrationBlockNumber(Long registrationBlockNumber) { this.registrationBlockNumber = registrationBlockNumber; }

    public String getRegistrationBlockHash() { return registrationBlockHash; }
    public void setRegistrationBlockHash(String registrationBlockHash) { this.registrationBlockHash = registrationBlockHash; }

    public boolean isRemovalConfirmed() { return removalConfirmed; }
    public void setRemovalConfirmed(boolean removalConfirmed) { this.removalConfirmed = removalConfirmed; }

    public Long getRemovalBlockNumber() { return removalBlockNumber; }
    public void setRemovalBlockNumber(Long removalBlockNumber) { this.removalBlockNumber = removalBlockNumber; }

    public String getRemovalBlockHash() { return removalBlockHash; }
    public void setRemovalBlockHash(String removalBlockHash) { this.removalBlockHash = removalBlockHash; }
}
