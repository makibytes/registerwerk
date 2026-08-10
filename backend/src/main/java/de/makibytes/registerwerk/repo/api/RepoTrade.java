package de.makibytes.registerwerk.repo.api;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "repo_trade")
public class RepoTrade {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Version private long version;
    @Column(name="rfq_id", nullable=false) private UUID rfqId;
    @Column(name="accepted_quote_id", nullable=false) private UUID acceptedQuoteId;
    @Column(name="cash_borrower_entity_id", nullable=false) private UUID cashBorrowerEntityId;
    @Column(name="cash_lender_entity_id", nullable=false) private UUID cashLenderEntityId;
    @Column(name="collateral_asset_id", nullable=false) private UUID collateralAssetId;
    @Column(name="collateral_quantity", nullable=false, precision=38, scale=18) private BigDecimal collateralQuantity;
    @Column(name="cash_amount", nullable=false, precision=38, scale=18) private BigDecimal cashAmount;
    @Column(name="cash_currency", nullable=false, length=3) private String cashCurrency;
    @Column(name="repo_rate", nullable=false, precision=12, scale=8) private BigDecimal repoRate;
    @Column(name="haircut_bps", nullable=false) private int haircutBps;
    @Column(name="start_date", nullable=false) private LocalDate startDate;
    @Column(name="end_date", nullable=false) private LocalDate endDate;
    @Column(name="repurchase_amount", nullable=false, precision=38, scale=18) private BigDecimal repurchaseAmount;
    @Enumerated(EnumType.STRING) @Column(name="settlement_method", nullable=false, length=20) private RepoTypes.SettlementMethod settlementMethod;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private RepoTypes.TradeStatus status = RepoTypes.TradeStatus.PENDING_OPEN_SETTLEMENT;
    @Column(name="open_cash_confirmed", nullable=false) private boolean openCashConfirmed;
    @Column(name="open_collateral_confirmed", nullable=false) private boolean openCollateralConfirmed;
    @Column(name="close_cash_confirmed", nullable=false) private boolean closeCashConfirmed;
    @Column(name="close_collateral_confirmed", nullable=false) private boolean closeCollateralConfirmed;
    @Column(name="margin_call_amount", precision=38, scale=18) private BigDecimal marginCallAmount;
    @Column(name="margin_call_due_at") private Instant marginCallDueAt;
    @Column(name="pending_substitution_asset_id") private UUID pendingSubstitutionAssetId;
    @Column(name="pending_substitution_quantity", precision=38, scale=18) private BigDecimal pendingSubstitutionQuantity;
    @Column(name="substitution_requested_by") private UUID substitutionRequestedBy;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt = Instant.now();
    @Column(name="updated_at", nullable=false) private Instant updatedAt = Instant.now();
    @PrePersist @PreUpdate void touch(){ updatedAt=Instant.now(); }

    public UUID getId(){return id;} public void setId(UUID v){id=v;} public UUID getRfqId(){return rfqId;} public void setRfqId(UUID v){rfqId=v;}
    public UUID getAcceptedQuoteId(){return acceptedQuoteId;} public void setAcceptedQuoteId(UUID v){acceptedQuoteId=v;}
    public UUID getCashBorrowerEntityId(){return cashBorrowerEntityId;} public void setCashBorrowerEntityId(UUID v){cashBorrowerEntityId=v;}
    public UUID getCashLenderEntityId(){return cashLenderEntityId;} public void setCashLenderEntityId(UUID v){cashLenderEntityId=v;}
    public UUID getCollateralAssetId(){return collateralAssetId;} public void setCollateralAssetId(UUID v){collateralAssetId=v;}
    public BigDecimal getCollateralQuantity(){return collateralQuantity;} public void setCollateralQuantity(BigDecimal v){collateralQuantity=v;}
    public BigDecimal getCashAmount(){return cashAmount;} public void setCashAmount(BigDecimal v){cashAmount=v;}
    public String getCashCurrency(){return cashCurrency;} public void setCashCurrency(String v){cashCurrency=v;}
    public BigDecimal getRepoRate(){return repoRate;} public void setRepoRate(BigDecimal v){repoRate=v;}
    public int getHaircutBps(){return haircutBps;} public void setHaircutBps(int v){haircutBps=v;}
    public LocalDate getStartDate(){return startDate;} public void setStartDate(LocalDate v){startDate=v;}
    public LocalDate getEndDate(){return endDate;} public void setEndDate(LocalDate v){endDate=v;}
    public BigDecimal getRepurchaseAmount(){return repurchaseAmount;} public void setRepurchaseAmount(BigDecimal v){repurchaseAmount=v;}
    public RepoTypes.SettlementMethod getSettlementMethod(){return settlementMethod;} public void setSettlementMethod(RepoTypes.SettlementMethod v){settlementMethod=v;}
    public RepoTypes.TradeStatus getStatus(){return status;} public void setStatus(RepoTypes.TradeStatus v){status=v;}
    public boolean isOpenCashConfirmed(){return openCashConfirmed;} public void setOpenCashConfirmed(boolean v){openCashConfirmed=v;}
    public boolean isOpenCollateralConfirmed(){return openCollateralConfirmed;} public void setOpenCollateralConfirmed(boolean v){openCollateralConfirmed=v;}
    public boolean isCloseCashConfirmed(){return closeCashConfirmed;} public void setCloseCashConfirmed(boolean v){closeCashConfirmed=v;}
    public boolean isCloseCollateralConfirmed(){return closeCollateralConfirmed;} public void setCloseCollateralConfirmed(boolean v){closeCollateralConfirmed=v;}
    public BigDecimal getMarginCallAmount(){return marginCallAmount;} public void setMarginCallAmount(BigDecimal v){marginCallAmount=v;}
    public Instant getMarginCallDueAt(){return marginCallDueAt;} public void setMarginCallDueAt(Instant v){marginCallDueAt=v;}
    public UUID getPendingSubstitutionAssetId(){return pendingSubstitutionAssetId;} public void setPendingSubstitutionAssetId(UUID v){pendingSubstitutionAssetId=v;}
    public BigDecimal getPendingSubstitutionQuantity(){return pendingSubstitutionQuantity;} public void setPendingSubstitutionQuantity(BigDecimal v){pendingSubstitutionQuantity=v;}
    public UUID getSubstitutionRequestedBy(){return substitutionRequestedBy;} public void setSubstitutionRequestedBy(UUID v){substitutionRequestedBy=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
