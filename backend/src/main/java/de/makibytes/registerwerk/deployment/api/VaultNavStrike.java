package de.makibytes.registerwerk.deployment.api;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vault_nav_strike")
public class VaultNavStrike {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "strike_id", nullable = false)
    private Long strikeId;

    @Column(name = "nav_per_share", nullable = false, precision = 38, scale = 18)
    private BigDecimal navPerShare;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "report_hash")
    private byte[] reportHash;

    @Column(name = "report_doc_id")
    private UUID reportDocId;

    @Column(name = "struck_by", nullable = false)
    private UUID struckBy;

    @Column(name = "struck_at", nullable = false)
    private Instant struckAt = Instant.now();

    @Column(name = "tx_hash", length = 80)
    private String txHash;

    /** FK to {@code chain_config} — set only once {@link #confirmed} is true, letting the
     *  reorg-retraction sweep find this row by (chainConfigId, blockNumber). */
    @Column(name = "chain_config_id")
    private UUID chainConfigId;

    @Column(name = "block_number")
    private Long blockNumber;

    /** Exact block occurrence which produced the current confirmation. */
    @Column(name = "block_hash", length = 128)
    private String blockHash;

    /** True once {@link VaultConfirmationListener} has confirmed {@link #txHash} SUCCESS and
     *  applied this strike to {@code AssetVaultState}. False (the default) means either no tx has
     *  been submitted yet, or it's still awaiting confirmation — scopes the listener's polling
     *  query so it shrinks over time instead of re-scanning every strike ever made. */
    @Column(name = "confirmed", nullable = false)
    private boolean confirmed = false;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public Long getStrikeId() { return strikeId; }
    public void setStrikeId(Long strikeId) { this.strikeId = strikeId; }

    public BigDecimal getNavPerShare() { return navPerShare; }
    public void setNavPerShare(BigDecimal navPerShare) { this.navPerShare = navPerShare; }

    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }

    public byte[] getReportHash() { return reportHash; }
    public void setReportHash(byte[] reportHash) { this.reportHash = reportHash; }

    public UUID getReportDocId() { return reportDocId; }
    public void setReportDocId(UUID reportDocId) { this.reportDocId = reportDocId; }

    public UUID getStruckBy() { return struckBy; }
    public void setStruckBy(UUID struckBy) { this.struckBy = struckBy; }

    public Instant getStruckAt() { return struckAt; }
    public void setStruckAt(Instant struckAt) { this.struckAt = struckAt; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }

    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }

    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
}
