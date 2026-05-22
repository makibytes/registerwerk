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
 * <p>Soft-deleted via {@code removedAt}: a non-null value means the investor was removed
 * from the on-chain IdentityRegistry (via {@code deleteIdentity()}).</p>
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

    /** Non-null when the investor has been removed from the on-chain IdentityRegistry. */
    @Column(name = "removed_at")
    private Instant removedAt;

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
}
