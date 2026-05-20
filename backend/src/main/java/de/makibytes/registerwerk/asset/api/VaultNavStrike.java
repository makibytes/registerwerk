package de.makibytes.registerwerk.asset.api;

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
}
