package de.makibytes.registerwerk.domain.erc3643;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a pluggable compliance module attached to a T-REX suite's ModularCompliance contract.
 * Modules encode regulatory transfer rules (e.g. max-holder-count, country restrictions).
 */
@Entity
@Table(name = "erc3643_compliance_module")
public class Erc3643ComplianceModule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The ERC-3643 suite this module belongs to. */
    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    /** On-chain address of the deployed compliance module contract. */
    @Column(name = "module_address", nullable = false, length = 66)
    private String moduleAddress;

    /**
     * Logical type identifier for the module, e.g. "MAX_BALANCE", "COUNTRY_RESTRICT",
     * "TRANSFER_FEES", "SUPPLY_LIMIT".
     */
    @Column(name = "module_type", nullable = false, length = 100)
    private String moduleType;

    /** Arbitrary module configuration stored as JSONB (kept for custom/future modules). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> parameters;

    // ── Structured fields for EwpgModularCompliance.TokenConfig ──────────────

    /** Maximum number of investors allowed to hold the token simultaneously; null = unlimited. */
    @Column(name = "max_investors")
    private Integer maxInvestors;

    /** Maximum token balance per investor (raw uint256 value); null = unlimited. */
    @Column(name = "max_balance", precision = 38, scale = 0)
    private BigInteger maxBalance;

    /** Minimum seconds between transfers from the same address; null or 0 = disabled. */
    @Column(name = "transfer_cooldown")
    private Integer transferCooldown;

    /**
     * ISO-3166-1 numeric country codes that are blocked from holding this token.
     * Stored as a PostgreSQL smallint[] array.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "blocked_countries", columnDefinition = "smallint[]")
    private List<Short> blockedCountries;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    /** Set when the module is removed from the compliance contract; null means currently active. */
    @Column(name = "removed_at")
    private Instant removedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSuiteId() { return suiteId; }
    public void setSuiteId(UUID suiteId) { this.suiteId = suiteId; }

    public String getModuleAddress() { return moduleAddress; }
    public void setModuleAddress(String moduleAddress) { this.moduleAddress = moduleAddress; }

    public String getModuleType() { return moduleType; }
    public void setModuleType(String moduleType) { this.moduleType = moduleType; }

    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }

    public Integer getMaxInvestors() { return maxInvestors; }
    public void setMaxInvestors(Integer maxInvestors) { this.maxInvestors = maxInvestors; }

    public BigInteger getMaxBalance() { return maxBalance; }
    public void setMaxBalance(BigInteger maxBalance) { this.maxBalance = maxBalance; }

    public Integer getTransferCooldown() { return transferCooldown; }
    public void setTransferCooldown(Integer transferCooldown) { this.transferCooldown = transferCooldown; }

    public List<Short> getBlockedCountries() { return blockedCountries; }
    public void setBlockedCountries(List<Short> blockedCountries) { this.blockedCountries = blockedCountries; }

    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
}
