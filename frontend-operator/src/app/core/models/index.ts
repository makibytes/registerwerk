// ── Token standards ───────────────────────────────────────────────────────────
export type TokenStandard =
  | 'ERC20' | 'ERC721' | 'ERC1155' | 'ERC3643' | 'CONF_ERC20' | 'CONF_ERC3643'
  | 'SPL' | 'SPL_2022' | 'STARKNET_ERC20' | 'STELLAR_ASSET' | 'CANTON_TOKEN'
  | 'ERC3525' | 'ERC4626' | 'ERC7540' | 'STARKNET_ERC3525'
  | 'DAML_BOND_FIXED' | 'DAML_BOND_FLOATING' | 'DAML_BOND_ZERO'
  | 'SPL_2022_BOND' | 'SPL_2022_CONFIDENTIAL';

export const BOND_STANDARDS: TokenStandard[] = [
  'ERC3525', 'DAML_BOND_FIXED', 'DAML_BOND_FLOATING', 'DAML_BOND_ZERO',
  'SPL_2022_BOND', 'STARKNET_ERC3525',
];

export const VAULT_STANDARDS: TokenStandard[] = ['ERC4626', 'ERC7540'];

// ── Bond terms ────────────────────────────────────────────────────────────────
export type DayCountConvention = 'ACT_360' | 'ACT_365' | 'ACT_ACT_ICMA' | 'THIRTY_360' | 'THIRTY_E_360';
export type PaymentFrequency = 'ANNUAL' | 'SEMI_ANNUAL' | 'QUARTERLY' | 'MONTHLY' | 'ZERO';

export interface CallEntry {
  callDate: string; // ISO-8601 date
  callPrice: number;
}

export interface AssetBondTerms {
  assetId: string;
  faceValue: number;
  currencyIso: string;
  issueDate: string;
  maturityDate: string;
  couponRate?: number;
  referenceRate?: string;
  spread?: number;
  dayCount: DayCountConvention;
  paymentFrequency: PaymentFrequency;
  callable: boolean;
  callSchedule?: CallEntry[];
  bondStatus: 'ACTIVE' | 'MATURED' | 'OVERDUE' | 'CALLED' | 'DEFAULTED' | 'REDEEMED';
  // Schedule conventions — backend defaults: ICMA / TARGET2.
  businessDayConvention?: BusinessDayConvention;
  holidayCalendar?: 'TARGET2';
  recordDateOffsetBd?: number;
  announcementLeadBd?: number;
  interestGraceDays?: number;
  principalGraceDays?: number;
  stubRule?: 'SHORT_FIRST';
}

export type BusinessDayConvention = 'MODIFIED_FOLLOWING' | 'FOLLOWING' | 'PRECEDING' | 'NONE';

/** One row of `GET /assets/{id}/bond-terms/schedule`. `amountPerUnit` is null for an unfixed floating coupon. */
export interface CouponScheduleEntry {
  periodNo: number;
  periodStart: string | null;
  periodEnd: string | null;
  announcementDate: string | null;
  recordDate: string | null;
  paymentDate: string;
  dayCountFraction: number | null;
  amountPerUnit: number | null;
  couponStatus: 'SCHEDULED' | 'OVERDUE' | 'PAID' | 'MISSED';
  paidDate: string | null;
  scheduleVersion: number;
}

/** Body of `POST /assets/{id}/terms-amendments` — only the fields sent are amended. */
export interface TermsAmendment {
  isin?: string;
  currency?: string;
  issueSize?: number;
  denomination?: number;
  issueDate?: string;
  maturityDate?: string;
  minInvestmentAmount?: number;
  maxHoldingAmount?: number;
  faceValue?: number;
  couponRate?: number;
  legalReference: string;
}

// ── Vault state ───────────────────────────────────────────────────────────────
export interface AssetVaultState {
  assetId: string;
  underlyingAssetId?: string;
  depositCap?: string; // BigInteger as string
  minSettlementDelay?: number; // seconds
  latestNavPerShare?: number;
  latestNavStrikeAt?: string;
}

export interface VaultNavStrike {
  id: string;
  assetId: string;
  strikeId: number;
  navPerShare: number;
  effectiveAt: string;
  struckBy: string;
  struckAt: string;
  txHash?: string;
}

export interface VaultRequest {
  id: string;
  assetId: string;
  requestId: string; // BigInteger as string
  requestType: 'DEPOSIT' | 'REDEEM';
  controllerAddr: string;
  ownerAddr: string;
  /** Who funded a deposit request — cancel refunds go here. */
  payerAddr?: string;
  /** BigInteger base units; exact strings above 2^53 (see parseJsonPreservingBigNumbers). */
  assetAmount?: string;
  shareAmount?: string;
  requestStatus: 'PENDING' | 'FULFILLED' | 'CANCELLED' | 'FORCE_CANCELLED';
  requestedAt: string;
  fulfilledAt?: string;
  fulfilledTx?: string;
  cancelledTx?: string;
  /** Executed NAV, read from the on-chain fulfilment event once confirmed. */
  navAtFulfill?: number;
  forcedToAddr?: string;
  legalBasis?: string;
  /** Set when a confirmed resolution could not be reconciled with its on-chain event. */
  reviewNote?: string;
  /** A fulfil/cancel tx was submitted and is not yet final. */
  awaitingConfirmation: boolean;
  /** Owner or refund recipient frozen / under Sperrvermerk — fulfil and cancel are refused. */
  complianceHold: boolean;
  complianceHoldReason?: string;
  /**
   * Forward pricing (T1-07): the instant from which a NAV struck on-chain may settle this request (its
   * "dealing day"). Absent for requests placed before the vault had a dealing cut-off and for settled ones.
   */
  dealingPoint?: string;
  /** A dealing point exists and the latest NAV was struck before it: fulfilment waits for the next NAV strike. */
  awaitingNavStrike?: boolean;
}

/** Live on-chain forward-pricing state of an ERC-7540 vault (T1-07). */
export interface VaultDealingState {
  /** The deployment is an ERC-7540 vault (only those have a dealing cut-off). */
  applicable: boolean;
  /** The on-chain read succeeded; when false every other field is unknown. */
  available: boolean;
  /** false: requests settle at the NAV struck at execution time (late-trading exposure). */
  configured: boolean;
  /** UTC seconds since midnight of the daily cut-off. */
  cutoffSecondsOfDay?: number;
  periodSeconds?: number;
  /** The dealing point a request placed now would receive. */
  nextDealingPoint?: string;
  /** When the latest NAV was struck on-chain (absent: never). */
  navStruckAt?: string;
}

/** GET /deployments/{id}/vault-state — confirmed strikes only. */
export interface VaultStateSummary {
  assetId: string;
  latestNavPerShare?: number;
  latestNavStrikeAt?: string;
  depositCap?: string;
  minSettlementDelay?: number;
  dealing?: VaultDealingState;
}

// ── ERC-3525 slot ─────────────────────────────────────────────────────────────
export interface AssetSlot {
  id: string;
  assetId: string;
  slotId: string; // BigInteger as string
  name?: string;
  metadata?: Record<string, unknown>;
  supplyCap?: string;
  paused: boolean;
  createdAt: string;
}

// ── Corporate actions ──────────────────────────────────────────────────────────
// PLEDGE was retired — never read/written by any code path (the real pledge/collateral
// mechanism lives in the lending module); see backend V14__corporate_action_issuer_workflow.sql.
export type CorporateActionType =
  | 'COUPON' | 'DIVIDEND' | 'SPLIT' | 'REVERSE_SPLIT' | 'CONVERSION'
  | 'REDEMPTION' | 'PARTIAL_REDEMPTION' | 'CALL'
  | 'CAPITAL_CALL' | 'INTEREST_PAYMENT';

/** PROPOSED/REJECTED are the issuer-proposal pre-states for issuer-initiated types (DIVIDEND,
 *  SPLIT, CALL): an issuer's proposal starts PROPOSED and an operator either approves it (→
 *  ANNOUNCED) or rejects it (→ REJECTED, terminal). System-raised COUPON/REDEMPTION skip
 *  PROPOSED entirely and start at ANNOUNCED. */
export type CorporateActionStatus =
  | 'PROPOSED' | 'ANNOUNCED' | 'SNAPSHOT_BLOCKED' | 'RECORD_DATE_SET' | 'COMPUTED' | 'AWAITING_SETTLEMENT'
  | 'SETTLED' | 'CLOSED' | 'CANCELLED' | 'REJECTED';

/** §10 eWpG register inspection request. */
export type InspectionLegalBasis = 'ISSUER' | 'HOLDER' | 'BENEFICIARY' | 'LEGITIMATE_INTEREST';
export type InspectionStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'FULFILLED';

export interface RegisterInspectionRequest {
  id: string;
  assetId: string;
  requesterEntityId: string | null;
  requesterName: string;
  requesterEmail: string | null;
  legalBasis: InspectionLegalBasis;
  statedInterest: string | null;
  status: InspectionStatus;
  decisionReason: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  fulfilledAt: string | null;
  /** True only when the claimed basis was checked against the register (issuer / active holder). */
  claimVerified?: boolean;
  createdAt: string;
}

/** §§21/22 eWpG register transfer to a successor registry operator. */
export type TransferStatus = 'INITIATED' | 'EXPORTED' | 'HANDED_OVER' | 'COMPLETED' | 'CANCELLED';

export interface RegisterTransfer {
  id: string;
  assetId: string;
  successorName: string;
  successorIdentifier: string | null;
  reason: string;
  status: TransferStatus;
  exportHash: string | null;
  /** Hash over the register content only (no export timestamp) - re-checked at completion. */
  registerContentHash?: string | null;
  successorOnchainAddress?: string | null;
  /** Asset status before the export froze the register (TRANSFER_PENDING). */
  previousAssetStatus?: string | null;
  onchainHandovers?: RegisterTransferDeploymentHandover[];
  onchainTxHash: string | null;
  operatorAttestation?: string | null;
  beneficiaryConsentRef?: string | null;
  initiatedBy: string | null;
  initiatedAt: string;
  exportedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

/** Per-deployment on-chain handover record: EVM = verified against registry()/owner(), else operator-attested. */
export interface RegisterTransferDeploymentHandover {
  deploymentId: string;
  chain: string | null;
  contractAddress?: string | null;
  txHash: string;
  verified: boolean;
  method: string;
  observedController?: string;
  recordedAt?: string;
}

/** Investor-side counterpart to {@link RegisterTransfer}: moves one holding to a successor
 *  registrar. Reuses {@link TransferStatus} — the lifecycle shape is identical. */
export interface PortfolioMigrationRequest {
  id: string;
  investorEntityId: string;
  assetId: string;
  holderId: string;
  destinationRegistrarName: string | null;
  destinationRegistrarIdentifier: string | null;
  destinationWalletAddress: string | null;
  reason: string;
  status: TransferStatus;
  exportHash: string | null;
  onchainTxHash: string | null;
  initiatedBy: string | null;
  initiatedAt: string;
  exportedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

export interface CorporateAction {
  id: string;
  assetId: string;
  actionType: CorporateActionType;
  status: CorporateActionStatus;
  announcementDate?: string;
  recordDate?: string;
  exDate?: string;
  paymentDate?: string;
  ratioNumerator?: number;
  ratioDenominator?: number;
  amountPerUnit?: number;
  totalAmount?: number;
  currency?: string;
  settlementTxHash?: string;
  settlementChain?: string;
  settledAt?: string;
  initiatedBy: string;
  /** The issuer's attestation that the underlying obligation/cash-leg is ready — the first of
   *  the two required parties before an operator can confirm settlement. May instead be an
   *  operator's audited override (issuerAttestationRef prefixed "OPERATOR_OVERRIDE: "). */
  issuerAttestedBy?: string;
  issuerAttestedAt?: string;
  issuerAttestationRef?: string;
  /** The operator's settlement confirmation — the second of the two required parties, refused
   *  while issuerAttestedAt is still unset. */
  dualControlApproverId?: string;
  dualControlApprovedAt?: string;
  notes?: string;
  /** Sum of unrounded minus rounded payable entitlements (T3-05). */
  roundingResidual?: number | null;
  /** C6: SHA-256 over the computed entitlements (entries, total, rounding residual) the sign-offs are bound to. */
  payoutDigest?: string | null;
  /** H6: set while the system itself holds the settlement back (it is not escalated to overdue / defaulted). */
  settlementHoldReason?: string | null;
  /** SETTLED but nominee-pool entitlements are unresolved, so the action is not closed (T3-02). */
  heldOutstanding?: boolean;
  /** Why the record-date snapshot is refused while status is SNAPSHOT_BLOCKED (register not reconciled). */
  snapshotBlockedReason?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LegalEntity {
  id: string;
  entityNumber: string;
  type: 'ISSUER' | 'INVESTOR' | 'AUDITOR';
  status: 'PENDING_ONBOARDING' | 'ACTIVE' | 'SUSPENDED' | 'DISSOLVED' | 'CLOSED' | 'PENDING_REACTIVATION';
  currentName: string;
  leiCode?: string;
  registrationNumber?: string;
  registrationCountry?: string;
  kycStatus: 'NOT_STARTED' | 'IN_PROGRESS' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  createdAt: string;
  updatedAt?: string;
  clientCategory?: ClientCategory | null;
  clientCategoryClassifiedAt?: string | null;
  assignedRelationshipManagerId?: string | null;
}

// ─── Finality gate / policy / effect journal ─────────────────────────────────

export type FinalityLevel = 'PROVISIONAL' | 'SAFE' | 'FINALIZED' | 'ORPHANED';
export type FinalityPolicyProfile = 'FAST' | 'BALANCED' | 'CONSERVATIVE';
export type FinalityPolicyScopeType = 'GLOBAL' | 'TOKEN_STANDARD' | 'ASSET';

/** Mirrors the backend's `FinalityPolicyAssignmentView`. */
export interface FinalityPolicyAssignmentView {
  id: string;
  scopeType: FinalityPolicyScopeType;
  tokenStandard: TokenStandard | null;
  assetId: string | null;
  profile: FinalityPolicyProfile;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors the backend's `FinalityPolicyOverrideView`. */
export interface FinalityPolicyOverrideView {
  id: string;
  assetId: string;
  operation: string;
  requiredLevel: FinalityLevel;
  reason: string;
  createdBy: string | null;
  createdAt: string;
}

/** Mirrors the backend's `ChainEffectView` — one row in the "unresolved compensation" queue. */
export interface ChainEffectView {
  id: string;
  chainConfigId: string;
  blockNumber: number;
  txHash: string | null;
  moduleName: string;
  effectType: string;
  entityType: string;
  entityId: string;
  assetId: string | null;
  category: 'RECOMPUTE' | 'INVERSE_FLIP' | 'IRREVERSIBLE';
  status: 'ACTIVE' | 'SETTLED' | 'COMPENSATING' | 'COMPENSATED' | 'COMPENSATION_FAILED' | 'IRREVERSIBLE_ESCALATED';
  attemptCount: number;
  /** Set on every terminal outcome — a real failure/remediation reason, or a benign "already
   *  undone" explanation alike. Not always an error, despite the name of the backend column this
   *  used to mirror before it was renamed for exactly that reason. */
  resolutionDetail: string | null;
  recordedAt: string;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  acknowledgeReason: string | null;
}

export type ClientCategory = 'RETAIL' | 'PROFESSIONAL' | 'ELIGIBLE_COUNTERPARTY';
export type KnowledgeExperienceLevel = 'NONE' | 'BASIC' | 'ADVANCED';
export type RiskTolerance = 'LOW' | 'MEDIUM' | 'HIGH';

export interface SuitabilityAssessment {
  id: string;
  entityId: string;
  knowledgeExperience: KnowledgeExperienceLevel;
  riskTolerance: RiskTolerance;
  investmentHorizonYears: number | null;
  financialSituationAdequate: boolean;
  notes: string | null;
  assessedAt: string;
  assessedBy: string | null;
}

export interface LegalEntityNameHistory {
  id: string;
  legalEntityId: string;
  previousName: string;
  newName: string;
  changeType: 'RENAME' | 'MERGER_ABSORBED' | 'MERGER_SURVIVOR' | 'ACQUISITION';
  relatedEntityId: string | null;
  effectiveDate: string;
  notes: string | null;
  recordedAt: string;
  recordedBy: string | null;
}

export interface EntityMergeRecordView {
  id: string;
  sourceEntityId: string;
  targetEntityId: string;
  mergeType: 'ABSORPTION' | 'CONSOLIDATION';
  effectiveDate: string;
  notes: string | null;
  recordedAt: string;
  recordedBy: string | null;
}

export type Jurisdiction = 'DE_EWPG' | 'LU_CSSF' | 'FR_AMF' | 'LI_TVTG';

export interface JurisdictionRequirement {
  jurisdiction: Jurisdiction;
  displayName: string;
  regulator: string;
  applicableLaw: string;
  requirements: DocumentRequirement[];
}

export interface DocumentRequirement {
  documentType: string;
  mandatory: boolean;
  localName: string;
  description: string;
  maxAgeDays: number | null;
}

export interface KycJurisdictionApproval {
  id: string;
  entityId: string;
  jurisdiction: Jurisdiction;
  jurisdictionDisplayName: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  approvedBy?: string;
  approvedAt?: string;
  expiresAt?: string;
  rejectionReason?: string;
  overrideNote?: string;
}

/** GwG §3 / AMLR Art. 42 beneficial-owner (UBO) registration. */
export interface BeneficialOwner {
  id: string;
  entityId: string;
  naturalPersonId: string;
  givenName: string;
  familyName: string;
  country?: string;
  pepStatus: 'UNKNOWN' | 'NOT_PEP' | 'DOMESTIC_PEP' | 'FOREIGN_PEP' | 'INTERNATIONAL_PEP' | 'PEP_FAMILY' | 'PEP_ASSOCIATE' | 'CONFIRMED_PEP';
  ownershipPct?: number;
  controlType: 'DIRECT_OWNERSHIP' | 'INDIRECT_OWNERSHIP' | 'OTHER_CONTROL' | 'LEGAL_REPRESENTATIVE' | 'TRUSTEE' | 'SENIOR_MANAGING_OFFICIAL';
  registeredAt: string;
  ceasedAt?: string;
  /** Set once verified against a stored KYC document; only verified owners count towards the identified share. */
  verifiedAt?: string;
  verificationDocumentId?: string;
  fallbackReason?: string;
}

export interface BeneficialOwnerRequest {
  person: {
    givenName: string;
    familyName: string;
    dateOfBirth?: string;
    nationality?: string;
    countryOfResidence?: string;
    taxId?: string;
    taxIdCountry?: string;
    addressLine1?: string;
    addressLine2?: string;
    city?: string;
    postalCode?: string;
    country?: string;
  };
  ownershipPct?: number;
  controlType: BeneficialOwner['controlType'];
  source?: string;
  /** Mandatory for SENIOR_MANAGING_OFFICIAL (then without ownershipPct): why no beneficial owner could be identified. */
  fallbackReason?: string;
}

export interface OwnershipSummary {
  activeCount: number;
  identifiedPct: number;
  unexplainedPct: number;
  smoFallback: boolean;
  coverageSufficient: boolean;
  requiredIdentifiedPct: number;
}

export interface KycComplianceResponse {
  jurisdiction: Jurisdiction;
  jurisdictionDisplayName: string;
  entityId: string;
  documents: DocumentStatus[];
  fullyCompliant: boolean;
  missingCount: number;
  expiredCount: number;
  tooOldCount: number;
}

export interface DocumentStatus {
  documentType: string;
  mandatory: boolean;
  localName: string;
  description: string;
  present: boolean;
  expired: boolean;
  tooOld: boolean;
  documentDate?: string;
  documentId?: string;
}

export interface Asset {
  id: string;
  assetNumber: string;
  issuerId: string;
  issuerName?: string;
  name: string;
  isin?: string;
  tokenStandard: TokenStandard;
  onchainLevel: 'NONE' | 'SIMPLE' | 'CONTROL';
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'ISSUED' | 'SUSPENDED' | 'REDEMPTION_PENDING' | 'REDEEMED' | 'TRANSFER_PENDING' | 'TRANSFERRED_OUT';
  jurisdiction?: Jurisdiction;
  totalSupply?: number;
  decimals?: number;
  createdAt: string;
  updatedAt?: string;
  hasTermSheet: boolean;
  currency?: string | null;
  issueSize?: number | null;
  denomination?: number | null;
  issueDate?: string | null;
  maturityDate?: string | null;
  targetMarketCategories?: ClientCategory[];
  targetMarketMinExperience?: KnowledgeExperienceLevel | null;
  minInvestmentAmount?: number | null;
  maxHoldingAmount?: number | null;
  /** Register reconciliation (T2-18): BLOCKED = finalized balances on wallets with no holder row. */
  holderSyncStatus?: 'OK' | 'BLOCKED';
  holderSyncBlockedReason?: string | null;
  holderSyncUnmappedWallets?: string[];
  /** T3-09: active register entries on this deployed asset that the chain does not back. */
  holderSyncOffchainRows?: number;
  lastSuccessfulHolderSyncAt?: string | null;
}

export interface InvestorLimit {
  id: string;
  assetId: string;
  investorEntityId: string;
  minInvestmentOverride: number | null;
  maxHoldingOverride: number | null;
  lockupUntil: string | null;
  updatedAt: string;
  updatedBy: string | null;
}

export interface AssetDocument {
  id: string;
  assetId: string;
  documentType: string;
  source: 'UPLOAD' | 'ONCHAIN_ERC1643' | 'ONCHAIN_TOKEN_URI' | 'ONCHAIN_SOLANA';
  mimeType: string;
  fileName?: string;
  sizeBytes?: number;
  contentHash?: string;
  chain?: string;
  network?: string;
  onchainUri?: string;
  uploadedAt: string;
  fetchedAt?: string;
  contentAvailable: boolean;
}

export interface AssetDeployment {
  id: string;
  assetId: string;
  chain: 'ETHEREUM' | 'POLYGON' | 'BASE' | 'FHENIX' | 'INCO' | 'SOLANA' | 'ARBITRUM' | 'AVALANCHE' | 'OPTIMISM' | 'STARKNET' | 'STELLAR' | 'CANTON';
  network: 'MAINNET' | 'TESTNET';
  contractAddress?: string;
  deploymentStatus: 'PENDING' | 'CONFIRMED' | 'FAILED';
  deployedAt?: string;
  txHash?: string;
}

/** Raw register row of `GET /assets/{id}/holders` (backend `HolderResponse`). */
export interface HolderRegisterRow {
  id: string;
  assetId: string;
  investorId: string | null;
  walletAddress: string | null;
  whitelisted: boolean | null;
  nominalAmount: number | string | null;
  entryType?: string | null;
}

/** Display model of one register holder; `percentage` is computed client-side from the listed rows. */
export interface AssetHolder {
  id?: string;
  investorId?: string | null;
  /** Wallet address; '' for a register entry without a wallet. */
  address: string;
  /** Same as `address`; kept explicit for wallet pickers. */
  walletAddress?: string;
  balance: number;
  percentage: number;
  whitelisted?: boolean | null;
}

/**
 * A single on-chain token transfer as recorded by the off-chain indexer (`token_transfer`
 * table) — distinct from `TxRecord`/`blockchain_transaction`, which is the registry's own
 * record of transactions *it* submitted. This is the indexer's view of everything that moved
 * on-chain, including transfers the registry didn't initiate itself. Mirrors the backend's
 * `TokenTransferResponse` record exactly (`indexer/web/dto/TokenTransferResponse.java`).
 */
export interface TokenTransferResponse {
  id: string;
  contractAddress: string;
  fromAddress: string | null;
  toAddress: string | null;
  tokenId: string | null;
  amount: string;
  eventType: string;
  txHash: string;
  blockNumber: number;
  occurredAt: string;
  explorerTxUrl: string | null;
  chainIdentifier: string;
  /**
   * The raw three-tier `FinalityLevel` name — PROVISIONAL (seen but not yet past the chain's
   * finality depth, can still be reorged out), SAFE (past a fast-but-not-final checkpoint, e.g.
   * Ethereum's `safe` tag), FINALIZED (past finality, safe to treat as settled), or ORPHANED (the
   * block that contained this transfer was reorged out — no longer exists on the canonical
   * chain). Stable across roles — always this raw enum name, regardless of who's asking; see
   * `finalityLabel` for the role-resolved display text.
   */
  finalityStatus: 'PROVISIONAL' | 'SAFE' | 'FINALIZED' | 'ORPHANED';
  /** Display text for `finalityStatus`, resolved server-side by the caller's role (see backend
   *  `TokenTransferMapper`) — equal to `finalityStatus` for operator staff, plain language
   *  ("Being confirmed", "Confirmed", "Settled — final", "Did not go through") for customer-side
   *  roles. The operator app always sees the former; render this, not `finalityStatus`, if a
   *  future view here is ever shown to a non-operator role. */
  finalityLabel: string;
}

/**
 * Operator visibility/recovery surface for `indexer_state` — one row per chain x indexer type.
 * Mirrors the backend's `IndexerStateResponse` record exactly
 * (`indexer/web/dto/IndexerStateResponse.java`).
 */
export interface IndexerStateResponse {
  id: string;
  chainConfigId: string;
  indexerType: 'GRAPH_NODE' | 'SOLANA_GEYSER' | 'SOLANA_POLL' | 'CANTON_STREAM' | 'STARKNET_POLL' | 'STELLAR_HORIZON';
  status: 'ACTIVE' | 'PAUSED' | 'ERROR';
  lastSyncedBlock: number | null;
  lastFinalBlock: number | null;
  lastSyncedSignature: string | null;
  lastSyncedAt: string | null;
  consecutiveErrors: number;
  lastError: string | null;
}

export interface KycDocument {
  id: string;
  entityId: string;
  documentType: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  uploadedAt: string;
  uploadedBy?: string;
  issueDate?: string;
  /** Mandatory on upload for passport / identity / register-extract documents. */
  expiresAt?: string;
}

export interface AuditEvent {
  id: string;
  eventType: string;
  subjectType: string;
  subjectId: string;
  actorId?: string;
  actorRole?: string;
  metadata?: Record<string, unknown>;
  occurredAt: string;
}

export interface ChainVerificationResult {
  /** Effective verdict: false while a broken run is unacknowledged, and when no run exists (UNKNOWN). */
  valid: boolean;
  rowsChecked: number;
  firstBrokenSequenceNo?: number | null;
  /** Null when no verification has ever run. */
  checkedAt: string | null;
  reason?: string | null;
  /** VALID, BROKEN (until a later valid run AND an acknowledgement) or UNKNOWN (no run recorded). */
  status?: 'VALID' | 'BROKEN' | 'UNKNOWN';
  verificationId?: string | null;
}

export interface OnboardingToken {
  token: string;
  entityId: string;
  expiresAt: string;
}

/** Customer support ticket — `support.web.SupportTicketAdminController`. */
export interface SupportTicket {
  id: string;
  entityId: string;
  createdBy: string;
  subject: string;
  description: string;
  category: 'TECHNICAL' | 'COMPLIANCE' | 'BILLING' | 'ASSET_ISSUE' | 'TRADING' | 'ONBOARDING' | 'OTHER';
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
  assignedTo?: string;
  resolutionNotes?: string;
  createdAt: string;
  updatedAt: string;
  resolvedAt?: string;
  closedAt?: string;
}

export interface SupportTicketMessage {
  id: string;
  authorId: string;
  authorIsOperator: boolean;
  body: string;
  createdAt: string;
}

export interface ChainDriftEvent {
  id: string;
  assetId: string;
  deploymentId: string;
  walletAddress: string;
  dbBalance: number;
  onchainBalance: number;
  delta: number;
  severity: 'WARNING' | 'CRITICAL';
  status: 'OPEN' | 'RESOLVED';
  firstDetectedAt: string;
  detectedAt: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
  resolutionNotes: string | null;
  /** NOT_INDEXED = the chain history behind the holder is not indexed, so on-chain balance is unknown, not zero (P4-01). */
  kind?: 'DRIFT' | 'NOT_INDEXED';
}

/**
 * CONFIRMED is the legacy (pre-payment-flow) state; new orders end in SETTLED, LAPSED or RELEASED. On a deployed asset
 * the mint is only submitted at settlement: SETTLEMENT_PENDING until it is final and indexed, SETTLEMENT_FAILED
 * (retryable) when it reverted.
 */
export type SubscriptionOrderStatus =
  'SUBMITTED' | 'ALLOCATED' | 'PAYMENT_CONFIRMED' | 'SETTLEMENT_PENDING' | 'SETTLEMENT_FAILED' | 'SETTLED' | 'CONFIRMED'
  | 'REJECTED' | 'CANCELLED' | 'LAPSED' | 'RELEASED';

export interface SubscriptionOrder {
  id: string;
  assetId: string;
  investorEntityId: string;
  walletAddress: string;
  requestedAmount: number;
  allocatedAmount: number | null;
  status: SubscriptionOrderStatus;
  submittedAt: string;
  allocatedAt: string | null;
  allocatedBy: string | null;
  confirmedAt: string | null;
  resultingHolderId: string | null;
  rejectionReason: string | null;
  acceptedAt: string | null;
  allocationExpiresAt: string | null;
  amountDue: number | null;
  paymentCurrency: string | null;
  paidAmount: number | null;
  refundDue: number | null;
  paymentReference: string | null;
  paymentValueDate: string | null;
  paymentConfirmedAt: string | null;
  settledAt: string | null;
  settlementTxId: string | null;
  lapsedAt: string | null;
  releaseReason: string | null;
  /** Why the mint of a SETTLEMENT_FAILED order did not happen. */
  settlementFailureReason?: string | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface EntityFilterParams {
  type?: string;
  status?: string;
  kycStatus?: string;
  search?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface AssetFilterParams {
  status?: string;
  tokenStandard?: string;
  issuerId?: string;
  search?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface AuditFilterParams {
  eventType?: string;
  subjectType?: string;
  subjectId?: string;
  actorId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export type SyncStatus = 'READY' | 'PENDING' | 'UPDATING';

export interface WalletBalance {
  chainConfigId: string;
  chainIdentifier: string;
  chainDisplayName: string;
  nativeCurrencySymbol: string;
  /** A string when it has 16+ significant digits (exact), else a number. */
  balance: number | string | null;
  error: string | null;
}

export interface OperatorWallet {
  id: string;
  name: string;
  type: 'EVM' | 'SOLANA' | 'CANTON';
  address: string;
  custodyType: 'SOFTWARE' | 'PKCS11' | 'KMS';
  keyReference: string | null;
  defaultForChains: string[]; // chain config UUIDs
  createdAt: string;
  updatedAt: string;
}

export interface WalletDefault {
  chainConfigId: string;
  chainIdentifier: string;
  chainDisplayName: string;
  walletId: string;
  walletName: string;
  walletAddress: string;
}

export type RpcNodeKind = 'DIRECT_RPC' | 'CHAINCACHE';

/** chaincache's real `addressTraceCapability` field is a nested status object, not a plain
 *  string — see ChaincacheClient.AddressTraceCapability on the backend. */
export interface RpcNodeAddressTraceCapability {
  attempted: boolean;
  lastSuccessful: boolean;
  lastAttemptAt: string | null;
}

/** Advisory snapshot of chaincache's own `GET /api/capabilities` response for one chain — see
 *  ChaincacheClient.ChainCapabilitiesProbe on the backend. Only present on a CHAINCACHE-kind node,
 *  and only once a probe has succeeded at least once. */
export interface RpcNodeCapabilities {
  finalityModel?: string;
  safeConfirmations?: number;
  finalizedConfirmations?: number;
  configuredApis?: string[];
  debugApiConfiguredOnAnyNode?: boolean;
  addressTraceCapability?: RpcNodeAddressTraceCapability | null;
  durableStreamAvailable?: boolean;
  durableProtocolVersion?: string;
  /** From chaincache's GET /api/chains — how many upstream RPC providers this workload has
   *  configured for the chain, and how many currently answer. Null when the enrichment probe
   *  itself failed (a degraded, not fatal, outcome — see ChaincacheClient#fetchNodeCounts). */
  configuredNodeCount?: number | null;
  availableNodeCount?: number | null;
  probedAt?: string;
}

export interface RpcNode {
  id: string;
  chainConfigId: string;
  chainIdentifier: string;
  url: string;
  label?: string;
  enabled: boolean;
  exclusive: boolean;
  latestBlockNumber?: number;
  blockLastAdvancedAt?: string;
  lastCheckedAt?: string;
  lastSuccessAt?: string;
  healthy: boolean;
  consecutiveFailures: number;
  lagFromBest?: number;
  syncing: boolean;
  kind: RpcNodeKind;
  managementUrl?: string;
  remoteChainKey?: string;
  capabilities?: RpcNodeCapabilities;
  /** Whether Registerwerk currently has a live durable-event WebSocket connection open to this
   *  node's chaincache workload — see ChaincacheDurableStreamManager. Only meaningful for
   *  kind === 'CHAINCACHE'; always false otherwise. */
  streamConnected?: boolean;
  /** Why the node is unhealthy/quarantined: CHAIN_MISMATCH, IMPLAUSIBLE_HEIGHT, RECOVERING, ... (P4C-1). */
  healthReason?: string | null;
  /** Genesis hash pinned for the node's chain (identical for every node of a chain). */
  pinnedGenesisHash?: string | null;
}

export interface ChainHealth {
  id: string;
  identifier: string;
  displayName: string;
  chainType: 'EVM' | 'SOLANA' | 'STARKNET' | 'STELLAR' | 'CANTON';
  networkType: 'MAINNET' | 'TESTNET';
  chainId?: number;
  enabled: boolean;
  nodes: RpcNode[];
}

export type FinalitySource = 'RPC_SELF_PROBE' | 'CHAINCACHE';

/** Full chain configuration — the "chain config screen" the operator UI previously had no way
 *  to edit finalityModel/avgBlockSeconds/finalitySource/rpcUrl/wsUrl/graphNodeUrl from. */
export interface ChainConfig {
  id: string;
  identifier: string;
  displayName: string;
  chainType: 'EVM' | 'SOLANA' | 'STARKNET' | 'STELLAR' | 'CANTON';
  networkType: 'MAINNET' | 'TESTNET';
  chainId?: number;
  blockExplorerUrl?: string;
  finalityModel: 'TAG_BASED' | 'DEPTH_BASED' | 'INSTANT';
  avgBlockSeconds?: number;
  finalitySource: FinalitySource;
  enabled: boolean;
}

export interface RegistryOverviewSummary {
  entityCount: number;
  issuerCount: number;
  investorCount: number;
  dualRoleCount: number;
  relationshipCount: number;
}

export interface RegistryEntityNode {
  id: string;
  entityNumber: string;
  currentName: string;
  storedType: 'ISSUER' | 'INVESTOR' | 'AUDITOR';
  roles: ('ISSUER' | 'INVESTOR' | 'AUDITOR')[];
  status: LegalEntity['status'];
  kycStatus: LegalEntity['kycStatus'];
  issuedAssetCount: number;
  investmentCount: number;
  linkedInvestorCount: number;
  linkedIssuerCount: number;
}

export interface RegistryRelationship {
  assetId: string;
  assetNumber: string;
  assetName: string;
  assetStatus: Asset['status'];
  issuerId: string;
  investorId: string;
  nominalAmount: number;
  whitelisted: boolean;
}

export interface RegistryOverview {
  generatedAt: string;
  summary: RegistryOverviewSummary;
  entities: RegistryEntityNode[];
  relationships: RegistryRelationship[];
}

// ─── Address Endpoints ────────────────────────────────────────────────────────

export type EndpointOwnerType = 'OPERATOR' | 'ENTITY';
export type EndpointAddressType = 'WALLET' | 'CONTRACT';
export type EndpointRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export interface Endpoint {
  id: string;
  ownerType: EndpointOwnerType;
  ownerId: string | null;
  address: string;
  addressType: EndpointAddressType;
  name: string;
  notes?: string;
  riskLevel?: EndpointRiskLevel;
  createdAt: string;
  updatedAt: string;
}

export interface EndpointCreateRequest {
  address: string;
  addressType: EndpointAddressType;
  name: string;
  notes?: string;
  riskLevel?: EndpointRiskLevel;
}

export interface EndpointUpdateRequest {
  name: string;
  notes?: string;
  riskLevel?: EndpointRiskLevel;
}

export interface AddressResolveResponse {
  resolutions: Record<string, string>;
}

// ─── Sanctions / PEP Screening ────────────────────────────────────────────────

export type ScreeningStatus = 'PENDING' | 'CLEAR' | 'HIT' | 'ERROR';
export type ScreeningTriggerType =
  | 'MANUAL'
  | 'KYC_SUBMISSION'
  | 'ONBOARDING'
  | 'BENEFICIAL_OWNER_ADD'
  | 'PERIODIC_REFRESH'
  | 'ERC3643_CLAIM';

export interface ScreeningRun {
  id: string;
  entityId: string | null;
  naturalPersonId: string | null;
  triggerType: ScreeningTriggerType;
  status: ScreeningStatus;
  provider: string;
  startedAt: string;
  completedAt: string | null;
}

export interface ScreeningHit {
  id: string;
  runId: string;
  listSource: string;
  category: 'SANCTIONS' | 'PEP' | 'ADVERSE_MEDIA';
  matchedField: string;
  matchedValue: string;
  matchScore: number | null;
  accepted: boolean | null;
  acceptReason: string | null;
  acceptedAt: string | null;
  /** FALSE_POSITIVE | CONFIRMED_PEP | null (still unresolved). */
  resolution?: 'FALSE_POSITIVE' | 'CONFIRMED_PEP' | null;
  carriedFromHitId?: string | null;
  /** A confirmed PEP whose enhanced due diligence review is due. */
  eddReviewDue?: string | null;
}

// ─── Holder Blocks (§16 eWpG Sperrvermerk) ───────────────────────────────────

export type BlockType =
  | 'PFANDRECHT' | 'PFAENDUNG' | 'GERICHTSBESCHLUSS' | 'NACHLASSSPERRE'
  | 'VERFUGUNGSVERBOT' | 'INSOLVENZ' | 'TOD' | 'REGULATORISCH';

/** EXPIRY_REVIEW: the stated expiry passed but the block keeps blocking until a confirmed lift (4-eyes). */
export type BlockStatus = 'ACTIVE' | 'EXPIRY_REVIEW' | 'LIFTED' | 'EXPIRED' | 'SUPERSEDED';

export interface HolderBlock {
  id: string;
  entityId: string | null;
  assetId: string | null;
  walletAddress: string;
  blockType: BlockType;
  status: BlockStatus;
  legalBasis: string;
  courtRef: string | null;
  documentId: string | null;
  startsAt: string;
  expiresAt: string | null;
  liftedAt: string | null;
  liftedBy: string | null;
  liftReason: string | null;
  onChainFreezeTxHash: string | null;
  createdBy: string;
  dualControlApproverId: string | null;
  expiryConfirmedByApprover?: boolean;
  expiryReviewAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HolderBlockRequest {
  entityId?: string;
  assetId?: string;
  walletAddress: string;
  blockType: BlockType;
  legalBasis: string;
  courtRef?: string;
  documentId?: string;
  expiresAt?: string;
}

// ─── Asset Token Admin Grants (delegatable forcedTransfer/forcedApprove/forceBurn) ──

export type TokenAdminGrantStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED';

export type TokenAdminGrantEligibilityBasis =
  | 'ISSUER_WALLET_BINDING' | 'INVESTOR_WHITELIST' | 'INVESTOR_WHITELIST_AND_ONCHAINID' | 'ENTITY_WALLET_BINDING';

export interface TokenAdminGrant {
  id: string;
  entityId: string;
  /** null = entity-wide, applies to every asset the entity is issuer/holder on. */
  assetId: string | null;
  walletAddress: string;
  capability: 'ASSET_TOKEN_ADMIN';
  status: TokenAdminGrantStatus;
  eligibilityBasis: TokenAdminGrantEligibilityBasis;
  chainConfigId: string | null;
  legalBasis: string;
  createdBy: string;
  dualControlApproverId: string | null;
  dualControlApprovedAt: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
  revokedBy: string | null;
  revokeReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TokenAdminGrantRequest {
  entityId?: string;
  walletAddress: string;
  chainConfigId?: string;
  legalBasis: string;
  expiresAt?: string;
}

// ─── DORA (Digital Operational Resilience Act) ───────────────────────────────

export type IctCategory = 'DATA_BREACH' | 'SYSTEM_OUTAGE' | 'RANSOMWARE' | 'THIRD_PARTY_FAILURE' | 'OTHER';
export type IctSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'MAJOR';
export type IctStatus =
  | 'DETECTED' | 'INVESTIGATING' | 'CONTAINED' | 'RESOLVED'
  | 'REPORTED_TO_AUTHORITY' | 'CLOSED';

export interface IctIncident {
  id: string;
  title: string;
  category: IctCategory;
  severity: IctSeverity;
  status: IctStatus;
  detectedAt: string;
  initialReportDeadline: string | null;
  finalReportDeadline: string | null;
  initialReportedAt: string | null;
  finalReportedAt: string | null;
  authorityRef: string | null;
  rootCause: string | null;
  remediationSteps: string | null;
  resolvedAt: string | null;
  awarenessAt?: string | null;
  classifiedAt?: string | null;
  classificationReason?: string | null;
  /** Reclassification to MAJOR is pending a reason/criteria; the clocks run from awareness. */
  classificationPending?: boolean;
  classificationDeadline?: string | null;
  intermediateReportDeadline?: string | null;
  intermediateReportedAt?: string | null;
  downgradedAt?: string | null;
  downgradeReason?: string | null;
  /** What still blocks closing: CLASSIFICATION_PENDING | INITIAL_REPORT_MISSING | FINAL_REPORT_MISSING. */
  closeBlockers?: string[];
}

export type ProviderCriticality = 'STANDARD' | 'IMPORTANT' | 'CRITICAL';

export interface ThirdPartyProvider {
  id: string;
  name: string;
  category: string;
  criticality: ProviderCriticality;
  lei: string | null;
  country: string | null;
  contractStart: string | null;
  contractEnd: string | null;
  subOutsourcing: boolean;
  subOutsourcingDetails: string | null;
  primaryContact: string | null;
  slaAvailabilityPct: number | null;
  rtoHours: number | null;
  rpoHours: number | null;
  notifiedAuthority: boolean;
  notifiedAt: string | null;
  notes: string | null;
}

export type ResilienceTestType = 'VULNERABILITY_SCAN' | 'SCENARIO_BASED' | 'TLPT';
export type ResilienceTestResult = 'PASSED' | 'FINDINGS_OPEN' | 'FAILED';

export interface ResilienceTest {
  id: string;
  testType: ResilienceTestType;
  scope: string;
  tlptRequired: boolean;
  thirdPartyProviderId: string | null;
  performedAt: string;
  nextDueDate: string | null;
  result: ResilienceTestResult;
  findings: string | null;
  testerName: string | null;
  reportRef: string | null;
}

// ─── Regulatory Reporting ────────────────────────────────────────────────────

/** A draft reporting export. `status` is transport-only (DRAFT_UNVALIDATED,
 *  NOT_TRANSPORTED, TRANSPORTED_UNVERIFIED, TRANSPORT_FAILED) — it never represents
 *  authority filing, acknowledgement, or acceptance. */
export interface RegulatorySubmission {
  id: string;
  report_type: string;
  jurisdiction: string | null;
  status: string;
  reporting_period_start: string | null;
  reporting_period_end: string | null;
  transported_at: string | null;
  transport_ref: string | null;
  transport_error: string | null;
  created_at: string;
}

/** Enriched view returned by the global open-hit work-queue endpoint. */
export interface OpenHitView {
  hitId: string;
  runId: string;
  entityId: string | null;
  naturalPersonId: string | null;
  listSource: string;
  category: 'SANCTIONS' | 'PEP' | 'ADVERSE_MEDIA';
  matchedField: string;
  matchedValue: string;
  matchScore: number | null;
  triggerType: string | null;
  runStatus: string | null;
  provider: string | null;
  createdAt: string | null;
  startedAt: string | null;
}

// ─── DSGVO Art. 17 erasure requests ─────────────────────────────────────────

/** A persisted DSAR erasure request, mirrors ErasureRequestResponse on the backend. */
export interface ErasureRequestView {
  id: string;
  entityId: string;
  requestedByUserId: string | null;
  status: 'REQUESTED' | 'IN_REVIEW' | 'COMPLETED' | 'REJECTED';
  requestedAt: string;
  dueAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  resolutionNote: string | null;
  retainedNoticeChannel?: string | null;
}

// ─── Ecosystem org identity ──────────────────────────────────────────────────

/** Onchain organization registration, mirrors OrgRegistrationResponse. */
export interface OrgRegistrationView {
  id: string;
  legalEntityId: string;
  entityName: string | null;
  chainConfigId: string;
  chainIdentifier: string | null;
  orgAddress: string;
  status: 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'FAILED';
  registeredTx: string | null;
  suspendedAt: string | null;
  suspensionReason: string | null;
  activeMemberCount: number;
  createdAt: string;
}

/** Member wallet bound to an organization, mirrors MemberWalletResponse. */
export interface OrgMemberWalletView {
  id: string;
  walletAddress: string;
  label: string | null;
  roles: string[];
  status: 'PENDING' | 'ACTIVE' | 'REMOVED' | 'FAILED';
  boundTx: string | null;
  createdAt: string;
  removedAt: string | null;
}

/** Ecosystem permission definition, mirrors PermissionDefinitionResponse. */
export interface PermissionDefinitionView {
  id: string;
  code: string;
  permissionHash: string;
  name: string;
  description: string | null;
  dappListingId: string | null;
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  createdAt: string;
}

/** Permission grant (org grant or role delegation), mirrors PermissionGrantResponse. */
export interface PermissionGrantView {
  id: string;
  permissionDefinitionId: string;
  permissionCode: string | null;
  orgRegistrationId: string;
  entityName: string | null;
  grantType: 'ORG' | 'ROLE';
  roleCode: string | null;
  roleRestricted: boolean;
  status: 'PENDING' | 'ACTIVE' | 'REVOKED' | 'FAILED';
  grantedTx: string | null;
  createdAt: string;
  revokedAt: string | null;
}

/** Ecosystem-wide trusted claim issuer, mirrors TrustedIssuerResponse. */
export interface EcosystemTrustedIssuerView {
  id: string;
  chainConfigId: string;
  issuerAddress: string;
  claimTopics: number[];
  legalEntityId: string | null;
  status: 'PENDING' | 'ACTIVE' | 'REMOVED' | 'FAILED';
  addedTx: string | null;
  createdAt: string;
  removedAt: string | null;
}

// ─── dApp marketplace (operator) ─────────────────────────────────────────────

export type DappListingStatus =
  | 'DRAFT' | 'SUBMITTED' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED'
  | 'PUBLISHED' | 'DEPRECATED' | 'DELISTED';

export type DappVersionStatus =
  | 'DRAFT' | 'SUBMITTED' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED'
  | 'PUBLISHED' | 'SUPERSEDED';

export interface DappListingView {
  id: string;
  slug: string;
  dappIdHash: string;
  name: string;
  category: string;
  status: DappListingStatus;
  chainConfigId: string;
  chainIdentifier: string | null;
  publisherEntityId: string;
  publisherName: string | null;
  currentVersionId: string | null;
  contactEmail: string | null;
  docsUrl: string | null;
  pricingNote: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DappVersionView {
  id: string;
  listingId: string;
  version: string;
  status: DappVersionStatus;
  manifestHash: string | null;
  signerWallet: string | null;
  signed: boolean;
  reviewNotes: string | null;
  submittedAt: string | null;
  reviewedAt: string | null;
  onchainTx: string | null;
  createdAt: string;
}

export interface DappRequiredPermissionView {
  permissionCode: string;
  permissionHash: string;
  claimTopics: number[];
  rationale: string | null;
}

export interface ReviewQueueItemView {
  versionId: string;
  listingId: string;
  slug: string;
  name: string;
  publisherName: string | null;
  version: string;
  status: DappVersionStatus;
  submittedAt: string | null;
}

export interface ReviewDetailView {
  listing: DappListingView;
  version: DappVersionView;
  manifestRaw: string | null;
  previousManifestRaw: string | null;
  requiredPermissions: DappRequiredPermissionView[];
  paymentMethods: PaymentMethodView[];
}

// ─── Payment rails (operator catalog + dApp-declared payment methods) ───────

export type PaymentRailType = 'STABLECOIN' | 'PONTES_API' | 'ERC7573_DVP' | 'OFFCHAIN_SEPA';

export interface PaymentRailChainAddressView {
  chainConfigId: string;
  chainIdentifier: string | null;
  tokenAddress: string;
}

export interface PaymentRailView {
  id: string;
  code: string;
  displayName: string;
  railType: PaymentRailType;
  currency: string;
  decimals: number | null;
  description: string | null;
  issuerName: string | null;
  issuerLei: string | null;
  micarAuthorization: string | null;
  emtFlag: boolean;
  whitePaperUrl: string | null;
  redemptionAtPar: boolean;
  enabled: boolean;
  /**
   * EFFECTIVE operator attestation: false when the attested content (token address, issuer, LEI,
   * currency, decimals, ...) changed since it was given. Operator-entered claims, never verified by Registerwerk.
   */
  micarVerified?: boolean;
  micarVerifiedAt?: string | null;
  micarVerifiedBy?: string | null;
  /** Why the rail was switched off automatically (MICAR_ATTESTATION_INVALIDATED / MICAR_ATTESTATION_MISSING). */
  disabledReason?: string | null;
  createdBy?: string | null;
  updatedBy?: string | null;
  createdAt: string;
  updatedAt: string;
  chainAddresses: PaymentRailChainAddressView[];
}

export interface PaymentRailRequest {
  code: string;
  displayName: string;
  railType: PaymentRailType;
  currency: string;
  decimals: number | null;
  description: string | null;
  issuerName: string | null;
  issuerLei: string | null;
  micarAuthorization: string | null;
  emtFlag: boolean;
  whitePaperUrl: string | null;
  redemptionAtPar: boolean;
  chainAddresses: { chainConfigId: string; tokenAddress: string }[];
}

/** A payment method a dApp version declares — a rail reference (resolved) or a custom descriptor. */
export interface PaymentMethodView {
  methodType: 'RAIL' | 'CUSTOM';
  railCode: string | null;
  displayName: string | null;
  railType: PaymentRailType | null;
  currency: string | null;
  emtFlag: boolean | null;
  issuerName: string | null;
  issuerLei: string | null;
  whitePaperUrl: string | null;
  redemptionAtPar: boolean | null;
  railEnabled: boolean;
  customName: string | null;
  customDescription: string | null;
  note: string | null;
}

// ── KYC queue and scoped review (T8-03) ─────────────────────────────────────

/** One entity awaiting a KYC / beneficial-owner / EDD decision, with the reasons it is queued. */
export interface KycQueueItem {
  entityId: string;
  entityName: string;
  homeJurisdiction: Jurisdiction;
  kycStatus: string;
  kycExpiryDate: string | null;
  reasons: string[];
}

export interface KycReviewDocument {
  id: string;
  documentType: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  contentHash: string;
  uploadedAt: string;
  expiresAt: string | null;
  issueDate: string | null;
}

export interface KycReviewChecklistDocument {
  documentType: string;
  mandatory: boolean;
  localName: string;
  description: string;
  present: boolean;
  expired: boolean;
  tooOld: boolean;
  documentDate: string | null;
  documentId: string | null;
}

export interface KycReviewBeneficialOwner {
  owner: BeneficialOwner;
  screeningUnresolved: boolean;
  eddInForce: boolean;
  eddReviewDue: string | null;
}

export interface KycDecisionRecord {
  id: string;
  approvedBy: string | null;
  secondApproverId: string | null;
  jurisdiction: Jurisdiction;
  expiryDate: string | null;
  checklistCompliant: boolean;
  overrideNote: string | null;
  identifiedPct: number;
  smoFallback: boolean;
  createdAt: string;
}

/**
 * What a KYC decision needs and nothing else: the compliance officer never gets the full entity, contact data,
 * wallets or balances through this view.
 */
export interface KycReview {
  entityId: string;
  entityNumber: string | null;
  legalName: string;
  entityType: string;
  entityStatus: string;
  registrationCountry: string | null;
  registrationNumber: string | null;
  leiCode: string | null;
  incorporationDate: string | null;
  homeJurisdiction: Jurisdiction;
  kycStatus: string;
  kycExpiryDate: string | null;
  documents: KycReviewDocument[];
  checklist: Omit<KycComplianceResponse, 'documents'> & { documents: KycReviewChecklistDocument[] };
  jurisdictionApprovals: KycJurisdictionApproval[];
  ownership: OwnershipSummary;
  beneficialOwners: KycReviewBeneficialOwner[];
  screening: { entityHitUnresolved: boolean; beneficialOwnerHitUnresolved: boolean; relyingOnStaleResult: boolean };
  gaps: string[];
  decisions: KycDecisionRecord[];
}

/** Display label of a wallet's custody type; HSM and KMS keys never leave the module and cannot be exported. */
export function custodyLabel(type: OperatorWallet['custodyType']): string {
  switch (type) {
    case 'PKCS11': return 'PKCS#11 HSM (non-exportable)';
    case 'KMS': return 'Cloud KMS (non-exportable)';
    default: return 'Encrypted software keystore';
  }
}

export function isExportableCustody(type: OperatorWallet['custodyType']): boolean {
  return type === 'SOFTWARE';
}
