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
    @Column(name="accepted_quote_id", nullable=false, updatable=false) private UUID acceptedQuoteId;
    @Column(name="cash_borrower_entity_id", nullable=false) private UUID cashBorrowerEntityId;
    @Column(name="cash_lender_entity_id", nullable=false) private UUID cashLenderEntityId;
    @Column(name="collateral_asset_id", nullable=false) private UUID collateralAssetId;
    @Column(name="collateral_quantity", nullable=false, precision=38, scale=18) private BigDecimal collateralQuantity;
    @Column(name="cash_amount", nullable=false, updatable=false, precision=38, scale=18) private BigDecimal cashAmount;
    @Column(name="cash_currency", nullable=false, updatable=false, length=3) private String cashCurrency;
    @Column(name="repo_rate", nullable=false, updatable=false, precision=12, scale=8) private BigDecimal repoRate;
    @Column(name="haircut_bps", nullable=false, updatable=false) private int haircutBps;
    @Column(name="start_date", nullable=false, updatable=false) private LocalDate startDate;
    @Column(name="end_date", nullable=false, updatable=false) private LocalDate endDate;
    @Column(name="repurchase_amount", nullable=false, updatable=false, precision=38, scale=18) private BigDecimal repurchaseAmount;
    @Enumerated(EnumType.STRING) @Column(name="settlement_method", nullable=false, length=20) private RepoTypes.SettlementMethod settlementMethod;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private RepoTypes.TradeStatus status = RepoTypes.TradeStatus.PENDING_OPEN_SETTLEMENT;
    @Column(name="open_cash_confirmed", nullable=false) private boolean openCashConfirmed;
    @Column(name="open_collateral_confirmed", nullable=false) private boolean openCollateralConfirmed;
    @Column(name="close_cash_confirmed", nullable=false) private boolean closeCashConfirmed;
    @Column(name="close_collateral_confirmed", nullable=false) private boolean closeCollateralConfirmed;
    @Column(name="margin_call_amount", precision=38, scale=18) private BigDecimal marginCallAmount;
    @Column(name="margin_call_due_at") private Instant marginCallDueAt;
    @Column(name="terms_hash", updatable=false) private String termsHash;
    @Column(name="accepted_quote_version", updatable=false) private Integer acceptedQuoteVersion;
    @Column(name="day_count_basis", nullable=false, updatable=false) private int dayCountBasis = 360;
    @Column(name="uti") private String uti;
    @Column(name="venue", nullable=false) private String venue = "BILATERAL_UNREGULATED";
    @Column(name="collateral_reuse_consent", nullable=false) private boolean collateralReuseConsent;
    @Column(name="open_cash_declared_at") private Instant openCashDeclaredAt;
    @Column(name="open_collateral_declared_at") private Instant openCollateralDeclaredAt;
    @Column(name="close_cash_declared_at") private Instant closeCashDeclaredAt;
    @Column(name="close_collateral_declared_at") private Instant closeCollateralDeclaredAt;
    @Column(name="margin_valuation_reference") private String marginValuationReference;
    @Column(name="margin_valuation_amount", precision=38, scale=18) private BigDecimal marginValuationAmount;
    @Column(name="margin_haircut_bps") private Integer marginHaircutBps;
    @Column(name="margin_delivered_at") private Instant marginDeliveredAt;
    @Column(name="margin_delivered_reference") private String marginDeliveredReference;
    @Column(name="default_notice_at") private Instant defaultNoticeAt;
    @Column(name="default_notice_by") private UUID defaultNoticeBy;
    @Enumerated(EnumType.STRING) @Column(name="default_notice_ground", length=30) private RepoTypes.DefaultGround defaultNoticeGround;
    @Column(name="defaulting_party_entity_id") private UUID defaultingPartyEntityId;
    @Enumerated(EnumType.STRING) @Column(name="default_ground", length=30) private RepoTypes.DefaultGround defaultGround;
    @Column(name="dispute_reason") private String disputeReason;
    @Enumerated(EnumType.STRING) @Column(name="pre_dispute_status", length=30) private RepoTypes.TradeStatus preDisputeStatus;
    @Column(name="disputed_at") private Instant disputedAt;
    @Column(name="disputed_by") private UUID disputedBy;
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
    public String getTermsHash(){return termsHash;} public void setTermsHash(String v){termsHash=v;}
    public Integer getAcceptedQuoteVersion(){return acceptedQuoteVersion;} public void setAcceptedQuoteVersion(Integer v){acceptedQuoteVersion=v;}
    public int getDayCountBasis(){return dayCountBasis;} public void setDayCountBasis(int v){dayCountBasis=v;}
    public String getUti(){return uti;} public void setUti(String v){uti=v;}
    public String getVenue(){return venue;} public void setVenue(String v){venue=v;}
    public boolean isCollateralReuseConsent(){return collateralReuseConsent;} public void setCollateralReuseConsent(boolean v){collateralReuseConsent=v;}
    public Instant getOpenCashDeclaredAt(){return openCashDeclaredAt;} public void setOpenCashDeclaredAt(Instant v){openCashDeclaredAt=v;}
    public Instant getOpenCollateralDeclaredAt(){return openCollateralDeclaredAt;} public void setOpenCollateralDeclaredAt(Instant v){openCollateralDeclaredAt=v;}
    public Instant getCloseCashDeclaredAt(){return closeCashDeclaredAt;} public void setCloseCashDeclaredAt(Instant v){closeCashDeclaredAt=v;}
    public Instant getCloseCollateralDeclaredAt(){return closeCollateralDeclaredAt;} public void setCloseCollateralDeclaredAt(Instant v){closeCollateralDeclaredAt=v;}
    public String getMarginValuationReference(){return marginValuationReference;} public void setMarginValuationReference(String v){marginValuationReference=v;}
    public BigDecimal getMarginValuationAmount(){return marginValuationAmount;} public void setMarginValuationAmount(BigDecimal v){marginValuationAmount=v;}
    public Integer getMarginHaircutBps(){return marginHaircutBps;} public void setMarginHaircutBps(Integer v){marginHaircutBps=v;}
    public Instant getMarginDeliveredAt(){return marginDeliveredAt;} public void setMarginDeliveredAt(Instant v){marginDeliveredAt=v;}
    public String getMarginDeliveredReference(){return marginDeliveredReference;} public void setMarginDeliveredReference(String v){marginDeliveredReference=v;}
    public Instant getDefaultNoticeAt(){return defaultNoticeAt;} public void setDefaultNoticeAt(Instant v){defaultNoticeAt=v;}
    public UUID getDefaultNoticeBy(){return defaultNoticeBy;} public void setDefaultNoticeBy(UUID v){defaultNoticeBy=v;}
    public RepoTypes.DefaultGround getDefaultNoticeGround(){return defaultNoticeGround;} public void setDefaultNoticeGround(RepoTypes.DefaultGround v){defaultNoticeGround=v;}
    public UUID getDefaultingPartyEntityId(){return defaultingPartyEntityId;} public void setDefaultingPartyEntityId(UUID v){defaultingPartyEntityId=v;}
    public RepoTypes.DefaultGround getDefaultGround(){return defaultGround;} public void setDefaultGround(RepoTypes.DefaultGround v){defaultGround=v;}
    public String getDisputeReason(){return disputeReason;} public void setDisputeReason(String v){disputeReason=v;}
    public RepoTypes.TradeStatus getPreDisputeStatus(){return preDisputeStatus;} public void setPreDisputeStatus(RepoTypes.TradeStatus v){preDisputeStatus=v;}
    public Instant getDisputedAt(){return disputedAt;} public void setDisputedAt(Instant v){disputedAt=v;}
    public UUID getDisputedBy(){return disputedBy;} public void setDisputedBy(UUID v){disputedBy=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
