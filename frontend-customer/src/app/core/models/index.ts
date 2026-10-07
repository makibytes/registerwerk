// ─── Enumerations ────────────────────────────────────────────────────────────

export type AssetStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'ISSUED'
  | 'SUSPENDED'
  | 'REDEMPTION_PENDING'
  | 'REDEEMED'
  | 'TRANSFER_PENDING'
  | 'TRANSFERRED_OUT';

export type Jurisdiction = 'DE_EWPG' | 'LU_CSSF' | 'FR_AMF' | 'LI_TVTG';

/** eWpG §8 Eintragungsart — how a holder's position is entered in the register. */
export type EntryType = 'COLLECTIVE' | 'INDIVIDUAL' | 'MIXED';

/**
 * Metadata for one downloadable register document — either a genuine statutory
 * register statement (§ 19 eWpG Registerauszug, or France's attestation
 * d'inscription en compte) or a labeled analogue / holding confirmation for a
 * collectively held (nominee) position. `statutory` distinguishes the two so the
 * UI never presents a holding confirmation as if it were the legal register
 * extract. See `RegisterDocumentService`.
 */
/** One entry in a callable bond's pre-captured call schedule (`AssetBondTerms.callSchedule`,
 *  JSONB on the backend) — the date/price an issuer's CALL proposal can reference by index
 *  instead of supplying a custom date/price (see `CorporateActionProposalValidator`). */
export interface CallEntry {
  callDate: string;
  callPrice: number;
}

/** Bond economic terms — `asset.web.BondTermsController`. Present only for bond-type assets. */
export interface AssetBondTerms {
  assetId: string;
  faceValue: number;
  currencyIso: string;
  issueDate: string;
  maturityDate: string;
  couponRate: number | null;
  referenceRate: string | null;
  spread: number | null;
  issuePrice: number;
  dayCount: 'ACT_360' | 'ACT_365' | 'ACT_ACT_ICMA' | 'THIRTY_360' | 'THIRTY_E_360';
  paymentFrequency: 'ANNUAL' | 'SEMI_ANNUAL' | 'QUARTERLY' | 'MONTHLY' | 'ZERO';
  callable: boolean;
  callSchedule: CallEntry[] | null;
  bondStatus: 'ACTIVE' | 'MATURED' | 'OVERDUE' | 'CALLED' | 'DEFAULTED' | 'REDEEMED';
}

/** One row of `GET /assets/{id}/bond-terms/schedule` (amountPerUnit null = floating, not yet fixed). */
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

// ── Corporate actions ──────────────────────────────────────────────────────────
// Only DIVIDEND/SPLIT/CALL are issuer-proposable today; the rest are system-raised
// (COUPON/REDEMPTION) or not yet buildable. PLEDGE was retired — see backend
// V14__corporate_action_issuer_workflow.sql.
export type CorporateActionType =
  | 'COUPON' | 'DIVIDEND' | 'SPLIT' | 'REVERSE_SPLIT' | 'CONVERSION'
  | 'REDEMPTION' | 'PARTIAL_REDEMPTION' | 'CALL'
  | 'CAPITAL_CALL' | 'INTEREST_PAYMENT';

/** PROPOSED/REJECTED are the issuer-proposal pre-states for issuer-initiated types (DIVIDEND,
 *  SPLIT, CALL). System-raised COUPON/REDEMPTION skip PROPOSED and start at ANNOUNCED. */
export type CorporateActionStatus =
  | 'PROPOSED' | 'ANNOUNCED' | 'SNAPSHOT_BLOCKED' | 'RECORD_DATE_SET' | 'COMPUTED' | 'AWAITING_SETTLEMENT'
  | 'SETTLED' | 'CLOSED' | 'CANCELLED' | 'REJECTED';

/** Issuer/investor-facing projection — `corporateactions.web.dto.CorporateActionView`.
 *  Deliberately omits `notes` and every actor id (`initiatedBy`, `issuerAttestedBy`,
 *  `dualControlApproverId`): callers need to know *whether* the two settlement-approval parties
 *  have signed off, not *who* on the operator side did. */
export interface CorporateActionView {
  id: string;
  assetId: string;
  actionType: CorporateActionType;
  status: CorporateActionStatus;
  announcementDate: string | null;
  recordDate: string | null;
  paymentDate: string | null;
  ratioNumerator: number | null;
  ratioDenominator: number | null;
  amountPerUnit: number | null;
  totalAmount: number | null;
  currency: string | null;
  settlementTxHash: string | null;
  settledAt: string | null;
  issuerAttestedAt: string | null;
  dualControlApprovedAt: string | null;
  createdAt: string;
  updatedAt: string;
  /** Settled, but some entitlements are still held back (older responses omit the field). */
  heldOutstanding?: boolean;
  /** Fingerprint of the computed amounts the issuer's attestation is bound to. */
  payoutDigest?: string | null;
}

export interface RegisterDocumentMeta {
  assetId: string;
  isin: string | null;
  assetName: string;
  jurisdiction: Jurisdiction | null;
  entryType: EntryType;
  docType: string;
  title: string;
  statutory: boolean;
  /** Set once the register was handed to a successor registrar: no document is offered here. */
  transferredTo?: string | null;
  transferredAt?: string | null;
}

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
  decidedAt: string | null;
  fulfilledAt: string | null;
  createdAt: string;
}

/** GwG §3 / AMLR Art. 42 beneficial-owner (UBO) registration — read-only on the customer side. */
export interface BeneficialOwner {
  id: string;
  entityId: string;
  naturalPersonId: string;
  givenName: string;
  familyName: string;
  country: string | null;
  pepStatus: 'UNKNOWN' | 'NOT_PEP' | 'DOMESTIC_PEP' | 'FOREIGN_PEP' | 'INTERNATIONAL_PEP' | 'PEP_FAMILY' | 'PEP_ASSOCIATE';
  ownershipPct: number | null;
  controlType: 'DIRECT_OWNERSHIP' | 'INDIRECT_OWNERSHIP' | 'OTHER_CONTROL' | 'LEGAL_REPRESENTATIVE' | 'TRUSTEE';
  registeredAt: string;
  ceasedAt: string | null;
}

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

/** Overall entity-level KYC decision — distinct from a single document's review status. */
export type KycEntityStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'APPROVED' | 'REJECTED' | 'EXPIRED';

/** Per-jurisdiction KYC approval record, including the rejection reason when applicable. */
export interface KycJurisdictionApproval {
  id: string;
  entityId: string;
  jurisdiction: Jurisdiction;
  jurisdictionDisplayName: string;
  status: KycEntityStatus;
  approvedBy: string | null;
  approvedAt: string | null;
  expiresAt: string | null;
  rejectionReason: string | null;
  overrideNote: string | null;
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

export type OnchainLevel = 'NONE' | 'SIMPLE' | 'CONTROL';

export type TokenStandard =
  | 'ERC20'
  | 'ERC721'
  | 'ERC1155'
  | 'ERC3643'
  | 'CONF_ERC20'
  | 'CONF_ERC3643'
  | 'SPL'
  | 'SPL_2022'
  | 'STARKNET_ERC20'
  | 'STELLAR_ASSET'
  | 'CANTON_TOKEN';

export type Chain =
  | 'ETHEREUM'
  | 'POLYGON'
  | 'BASE'
  | 'FHENIX'
  | 'INCO'
  | 'SOLANA'
  | 'ARBITRUM'
  | 'AVALANCHE'
  | 'OPTIMISM'
  | 'STARKNET'
  | 'STELLAR'
  | 'CANTON';

export type Network = 'MAINNET' | 'TESTNET';

export type DeploymentStatus = 'PENDING' | 'CONFIRMED' | 'FAILED';

export type KycStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type UserRole =
  | 'ISSUER'
  | 'INVESTOR'
  | 'COMPANY_ADMIN'
  | 'REGISTRY_ADMIN'
  | 'AUDIT'
  | 'SUPPORT_AGENT'
  | 'TRADER';

export type ExternalReferenceSubjectType =
  | 'LEGAL_ENTITY'
  | 'ASSET'
  | 'ASSET_HOLDER'
  | 'ERC3643_IDENTITY_REGISTRY_ENTRY';

export interface CompanyExternalReferenceRecord {
  subjectType: ExternalReferenceSubjectType;
  subjectId: string;
  externalId: string;
  displayName: string;
  contextLabel: string | null;
  relatedAssetId: string | null;
  updatedAt: string;
}

// ─── Domain Models ────────────────────────────────────────────────────────────

export interface LegalEntity {
  id: string;
  legalName: string;
  registrationNumber: string;
  jurisdiction: string;
  entityType: string;
  kycStatus: KycStatus;
  kycVerifiedAt: string | null;
  onboardingTokenUsed: boolean;
  idpIssuerUrl: string | null;
  idpClientId: string | null;
  createdAt: string;
  updatedAt: string;
  externalId: string | null;
}

export interface Asset {
  id: string;
  assetNumber: string;
  name: string;
  isin: string | null;
  onchainLevel: OnchainLevel;
  tokenStandard: TokenStandard | null;
  status: AssetStatus;
  chain: Chain | null;
  network: Network | null;
  issuerId: string;
  jurisdiction: Jurisdiction | null;
  createdAt: string;
  updatedAt: string;
  hasTermSheet: boolean;
  externalId: string | null;
  currency: string | null;
  issueSize: number | null;
  denomination: number | null;
  issueDate: string | null;
  maturityDate: string | null;
  /** Register reconciliation: BLOCKED = the holder register could not be derived from the chain (read-only warning). */
  holderSyncStatus?: 'OK' | 'BLOCKED';
  holderSyncBlockedReason?: string | null;
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
  chain: Chain;
  network: Network;
  contractAddress: string | null;
  deploymentStatus: DeploymentStatus;
  deployedAt: string | null;
  createdAt: string;
}

export interface AssetHolder {
  id: string;
  assetId: string;
  assetName?: string;
  investorEntityId: string;
  walletAddress: string;
  nominalAmount: number;
  whitelisted: boolean;
  whitelistedAt: string | null;
  createdAt: string;
  updatedAt: string;
  externalId: string | null;
}

export interface KycDocument {
  id: string;
  entityId: string;
  documentType: string;
  fileName: string;
  fileSize: number;
  uploadedAt: string;
  status: KycStatus;
}

/** Customer support ticket — `support.web.MeSupportTicketController`. */
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

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

/** Mirrors the backend's `shared.api.PageResponse` exactly — a different shape from the
 *  `PageResponse` above (that one is Spring Data's native page JSON, used elsewhere in this app;
 *  this backend DTO uses `page`, not `number`, and has no `first`/`last`). Used by
 *  `TokenHistoryService`, the only current caller of this shape in this app. */
export interface BackendPageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

/**
 * A single on-chain token transfer as recorded by the off-chain indexer. Mirrors the backend's
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
  /** Raw `FinalityLevel` name — stable across roles. Render `finalityLabel`, not this, for
   *  display text; this app's users always get plain language via that field. */
  finalityStatus: 'PROVISIONAL' | 'SAFE' | 'FINALIZED' | 'ORPHANED';
  /** Plain-language display text, resolved server-side for this app's customer-side roles
   *  ("Being confirmed", "Confirmed", "Settled — final", "Did not go through"). */
  finalityLabel: string;
}

// ─── Issuance Wizard Form Model ───────────────────────────────────────────────

export interface IssuanceWizardData {
  // Step 1 — Details
  name: string;
  isin: string | null;
  onchainLevel: OnchainLevel;
  // Step 2 — Chain & Standard
  chain: Chain;
  network: Network;
  tokenStandard: TokenStandard;
}

// ─── Investment-specific Models ───────────────────────────────────────────────

export interface InvestmentRecord {
  id: string;
  assetId: string;
  assetNumber: string | null;
  assetName: string | null;
  isin: string | null;
  tokenStandard: TokenStandard | null;
  assetStatus: AssetStatus | null;
  investorId: string;
  walletAddress: string;
  whitelisted: boolean;
  whitelistTxHash: string | null;
  nominalAmount: number;
  acquisitionDate: string | null;
  createdAt: string;
  updatedAt: string;
  externalId: string | null;
  chain: Chain | null;
  currency: string | null;
  denomination: number | null;
}

export interface InvestmentSummary {
  assetId: string;
  assetName: string;
  nominalAmount: number;
  walletAddress: string;
  whitelisted: boolean;
}

// ─── Subscription / Primary-market Orders ─────────────────────────────────────

/** CONFIRMED is the legacy (pre-payment-flow) state; new orders end in SETTLED, LAPSED or RELEASED. */
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
  paymentConfirmedAt: string | null;
  settledAt: string | null;
  lapsedAt: string | null;
  releaseReason: string | null;
}

// ─── Company / User Administration ───────────────────────────────────────────

export interface CompanyUser {
  id: string;
  email: string;
  name: string;
  roles: UserRole[];
  entityId: string;
  enabled: boolean;
  lastLoginAt: string | null;
  authProvider: 'LOCAL' | 'ENTRA';
  passwordSetupRequired: boolean;
}

// ─── Onboarding ───────────────────────────────────────────────────────────────

export interface OnboardingTokenInfo {
  entityId: string;
  entityName: string;
  entityRegistrationNumber: string;
  registrationCountry: string;
  expiresAt: string;
  alreadyUsed: boolean;
}

export interface OnboardingCompleteRequest {
  token: string;
  adminEmail: string;
  adminName: string;
  password: string;
}

// ─── IdP Settings ─────────────────────────────────────────────────────────────

export interface IdpSettings {
  issuerUrl: string;
  clientId: string;
  /**
   * How this organisation's users are hosted. Operator-controlled and read-only here:
   * WORKFORCE_MEMBER / WORKFORCE_GUEST (the operator manages their MFA) or FEDERATED
   * (this organisation's own tenant does).
   */
  identityModel: 'LOCAL' | 'WORKFORCE_MEMBER' | 'WORKFORCE_GUEST' | 'FEDERATED';
  /**
   * Whether MFA performed in this organisation's own tenant is accepted. Operator-controlled —
   * an organisation vouching for its own MFA would let it lower the bar applied to its users.
   */
  idpMfaTrusted: boolean;
  lifecycleManagedExternally: boolean;
}

export interface PublicUserActionTokenInfo {
  email: string;
  name: string;
}

// ─── Trading ──────────────────────────────────────────────────────────────────

export type TradingVenueCode = 'SIMULATED' | 'ASSETERA' | 'ARCHAX' | 'TALOS';

export type TradingAssetType = 'EQUITY' | 'BOND' | 'FUND' | 'NOTE' | 'COMMODITY' | 'OTHER';

export type PaymentOption =
  | 'NATIVE_CHAIN_CURRENCY'
  | 'STABLECOIN'
  | 'CBMT'
  | 'PONTES_TARGET'
  | 'OFFCHAIN_SEPA';

export type TradingOrderType = 'MARKET' | 'LIMIT' | 'IOC' | 'FOK';

export type ListingStatus = 'OPEN' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELLED';

export type SettlementStatus = 'PENDING' | 'AWAITING_SELLER_CONFIRMATION' | 'PAYMENT_UNRESOLVED' | 'SETTLED' | 'FAILED' | 'CANCELLED' | 'REFUNDED';

export type WalletPreferenceMode = 'GLOBAL_DEFAULT' | 'ASSET_TYPE_DEFAULT' | 'ENDPOINT' | 'CUSTOM_ADDRESS';

export type WalletTargetType = 'ENDPOINT' | 'CUSTOM_ADDRESS';

export interface TradingVenue {
  code: TradingVenueCode;
  displayName: string;
  connected: boolean;
  executable: boolean;
  supportedOrderTypes: TradingOrderType[];
  summary: string;
}

export interface CompanyTraderWalletDefault {
  id?: string;
  assetType: TradingAssetType | null;
  targetType: WalletTargetType;
  endpointId: string | null;
  walletAddress: string | null;
}

export interface CompanyTraderSettings {
  defaultPaymentOption: PaymentOption;
  immediateSettlementEnabled: boolean;
  walletDefaults: CompanyTraderWalletDefault[];
}

export interface SellableHolding {
  holderId: string;
  assetId: string;
  assetNumber: string;
  assetName: string;
  isin: string | null;
  assetType: TradingAssetType;
  tokenStandard: TokenStandard;
  chain: Chain | null;
  ownedQuantity: number;
  availableQuantity: number;
  walletAddress: string;
  jurisdiction: Jurisdiction | null;
  /** False for a chain-deployed asset while off-chain settlement is disabled (listing would be refused). */
  listable: boolean;
  /** True when listing is allowed only as a simulated, register-only settlement (demo). */
  simulatedOffchainSettlement: boolean;
}

export interface TradeListing {
  id: string;
  venueCode: TradingVenueCode;
  assetId: string;
  assetNumber: string;
  assetName: string;
  isin: string | null;
  assetType: TradingAssetType;
  tokenStandard: TokenStandard;
  chain: Chain | null;
  status: ListingStatus;
  quantityTotal: number;
  quantityAvailable: number;
  pricePerUnit: number;
  allowedPaymentOptions: PaymentOption[];
  createdAt: string;
  lastTradePrice?: number | null;
  /** Seller opted in to the demo instant path (only ever true while the demo property is on). */
  allowInstantSettlement?: boolean;
  /** ISO 4217 settlement currency; null on legacy listings ("currency not recorded"). */
  currency?: string | null;
  paymentRailCode?: string | null;
  /** Bilateral listing: the only entity that may buy; null = open listing. */
  targetEntityId?: string | null;
  lastTradePriceIndicative?: boolean;
}

export interface TradingOffer {
  listingId: string;
  venueCode: TradingVenueCode;
  venueDisplayName: string;
  assetId: string;
  assetNumber: string;
  assetName: string;
  isin: string | null;
  assetType: TradingAssetType;
  tokenStandard: TokenStandard;
  chain: Chain | null;
  quantityAvailable: number;
  pricePerUnit: number;
  allowedPaymentOptions: PaymentOption[];
  supportedOrderTypes: TradingOrderType[];
  createdAt: string;
  lastTradePrice?: number | null;
  currency?: string | null;
  paymentRailCode?: string | null;
  /** Bilateral listing addressed to the viewer. */
  targeted?: boolean;
}

/** Tenant-visible trading switches (`GET /trading/config`). */
export interface TradeConfig {
  demoInstantSettlementAvailable: boolean;
  maxOpenReservationsPerBuyer: number;
  reservationCooldownHours: number;
  pendingTimeoutHours: number;
}

export interface TradeNote {
  id: string;
  actorRole: string;
  actorEntityId: string | null;
  text: string;
  createdAt: string;
}

/** Enabled payment rail from `GET /payment-rails/catalog` (subset used by the trading desk). */
export interface CatalogPaymentRail {
  id: string;
  code: string;
  displayName: string;
  railType: string;
  currency: string;
  decimals: number | null;
}

export interface TradeExecution {
  id: string;
  side: 'BUY' | 'SELL';
  listingId: string;
  venueCode: TradingVenueCode;
  assetId: string;
  assetNumber: string;
  assetName: string;
  isin: string | null;
  assetType: TradingAssetType;
  tokenStandard: TokenStandard;
  chain: Chain | null;
  orderType: TradingOrderType;
  executedQuantity: number;
  unitPrice: number;
  totalPrice: number;
  paymentOption: PaymentOption;
  settlementStatus: SettlementStatus;
  walletPreferenceMode: WalletPreferenceMode;
  walletEndpointId: string | null;
  walletAddress: string;
  createdAt: string;
  settledAt: string | null;
  failureReason: string | null;
  paymentReference: string | null;
  paymentDeclaredAt: string | null;
  /** Demo instant path: the register moved with NO cash leg. */
  instantSettlement?: boolean;
  disputeReason?: string | null;
  unresolvedAt?: string | null;
  unresolvedReason?: string | null;
  buyerCooldownUntil?: string | null;
  /** ISO 4217; null on legacy trades ("currency not recorded"). */
  currency?: string | null;
  paymentRailCode?: string | null;
  totalPriceUnrounded?: number | null;
  priceRoundingScale?: number | null;
  priceRoundingMode?: string | null;
  relatedParty?: boolean;
  relatedPartyReasons?: string | null;
}

// ─── Page / Query Params ──────────────────────────────────────────────────────

export interface PageParams {
  page?: number;
  size?: number;
  sort?: string;
  [key: string]: string | number | boolean | undefined;
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

// ─── Ecosystem org identity ──────────────────────────────────────────────────

/** The company's onchain organization registration on one chain. */
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

/** A wallet bound to the company's organization. */
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

/** Nonce challenge to sign with personal_sign before binding a wallet. */
export interface WalletChallengeView {
  nonce: string;
  message: string;
  expiresAt: string;
}

// ─── dApp marketplace ────────────────────────────────────────────────────────

export type DappListingStatus =
  | 'DRAFT' | 'SUBMITTED' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED'
  | 'PUBLISHED' | 'DEPRECATED' | 'DELISTED';

export type DappVersionStatus =
  | 'DRAFT' | 'SUBMITTED' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED'
  | 'PUBLISHED' | 'SUPERSEDED';

/** Marketplace listing, mirrors ListingResponse. */
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

/** Manifest version of a listing, mirrors VersionResponse. */
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

export interface ManifestValidationView {
  valid: boolean;
  errors: string[];
  manifestHash: string | null;
}

export interface CatalogCardView {
  slug: string;
  name: string;
  category: string;
  publisherName: string | null;
  version: string | null;
  requiredPermissionCount: number;
  pricingNote: string | null;
  paymentMethods: PaymentMethodView[];
  updatedAt: string;
}

export interface CatalogDetailView {
  listing: DappListingView;
  version: DappVersionView;
  manifestRaw: string;
  manifestSignature: string | null;
  requiredPermissions: DappRequiredPermissionView[];
  paymentMethods: PaymentMethodView[];
}

// ─── Payment rails (registry-provided cash leg for dApps) ───────────────────

export type PaymentRailType = 'STABLECOIN' | 'PONTES_API' | 'ERC7573_DVP' | 'OFFCHAIN_SEPA';

export interface PaymentRailChainAddressView {
  chainConfigId: string;
  chainIdentifier: string | null;
  tokenAddress: string;
}

/** Enabled payment rail as offered in the operator's catalog (for publishers to reference). */
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
  createdAt: string;
  updatedAt: string;
  chainAddresses: PaymentRailChainAddressView[];
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
  /** Operator-entered claim, see `attestationStatus`. */
  micarAuthorization?: string | null;
  /** OPERATOR_ATTESTED = effective attestation bound to the current token/issuer; UNVERIFIED = claims only. */
  attestationStatus?: 'OPERATOR_ATTESTED' | 'UNVERIFIED' | 'NOT_APPLICABLE';
  micarVerifiedAt?: string | null;
  /** Why a disabled rail is off, e.g. MICAR_ATTESTATION_INVALIDATED or MICAR_ATTESTATION_MISSING. */
  railDisabledReason?: string | null;
  attestationNotice?: string | null;
  customName: string | null;
  customDescription: string | null;
  note: string | null;
}

/** Permission grant of the company's org: org-level grant or role delegation. */
export interface PermissionGrantView {
  id: string;
  permissionDefinitionId: string;
  permissionCode: string | null;
  orgRegistrationId: string;
  grantType: 'ORG' | 'ROLE';
  roleCode: string | null;
  roleRestricted: boolean;
  status: 'PENDING' | 'ACTIVE' | 'REVOKED' | 'FAILED';
  grantedTx: string | null;
  createdAt: string;
  revokedAt: string | null;
}

// ── Securities-backed lending (isolated on-chain lending markets) ───────────
//
// WAD-scaled / raw on-chain integer fields (healthFactorWad, currentDebt, collateralAmount,
// currentClaim, pricePerUnit, maxBorrowAmount, utilizationWad, borrowRateWad, baseRateWad,
// slopeWad) are typed `string`, not `number` — see core/api/json-bigint.util.ts for why a plain
// `number` would silently lose precision for any realistic WAD value (e.g. health factor 1.5 =
// 1500000000000000000, far beyond Number.MAX_SAFE_INTEGER).

export type LendingMarketStatus = 'ACTIVE' | 'PAUSED' | 'RETIRED';

export type LendingPositionStatus = 'OPEN' | 'CLOSED' | 'LIQUIDATED';

export interface LendingMarket {
  id: string;
  chainConfigId: string;
  /** EVM chain id and display name of the market's chain; absent on older cached responses (actions then fail closed). */
  chainId?: number | null;
  chainName?: string | null;
  marketAddress: string;
  vaultAddress: string | null;
  collateralAssetId: string | null;
  collateralAssetName: string | null;
  collateralIsin: string | null;
  collateralTokenAddress: string;
  loanTokenAddress: string;
  loanRailCode: string | null;
  loanTokenDecimals: number | null;
  maxLtvBps: number | null;
  lltvBps: number;
  liquidationBonusBps: number;
  baseRateWad: string;
  slopeWad: string;
  maxPriceAgeSeconds: string | null;
  liquidationGracePeriodSeconds: string | null;
  priceOracleAddress: string;
  status: LendingMarketStatus;
  jurisdiction: Jurisdiction | null;
  micarApplicable: boolean | null;
  defiInteropModel: 'NONE' | 'NOMINEE_POOL' | 'ORACLE_ONLY' | null;
  createdAt: string;
  /**
   * The deployed market predates today's risk checks (LLTV × (1 + bonus) within the oracle's
   * deviation haircut, oracle quoted in the loan token, operating-org binding). Kept listed so
   * existing borrowers and lenders can repay, claim and withdraw — never offer new borrowing or
   * supply on it.
   */
  riskParametersLegacy: boolean;
  /** Org operating the market on-chain; null for a legacy market. */
  operatorOrg: string | null;
  /** The market's fixed reserve recipient; null for a legacy market. */
  treasury: string | null;
  /**
   * Why `status` reads PAUSED although the market is registered ACTIVE. Hide borrowing when set.
   */
  pauseReason?: 'COLLATERAL_SHORTFALL' | 'BINDING_UNVERIFIED' | 'BORROW_PAUSED_ONCHAIN' | 'CHAIN_READ_FAILED' | `COLLATERAL_ASSET_${string}` | `COLLATERAL_BOND_${string}` | string | null;
  /** False when re-verification found the factory / collateral / loan-token binding broken. */
  bindingVerified?: boolean;
  bindingFailure?: string | null;
  collateralShortfall?: boolean;
}

export interface LendingQuote {
  marketId: string;
  collateralAmount: string;
  pricePerUnit: string;
  priceUpdatedAt: string;
  maxBorrowAmount: string;
  maxLtvBps: number;
  lltvBps: number;
  utilizationWad: string;
  borrowRateWad: string;
  availableLiquidity: string;
  oracleReliable: boolean;
}

export interface LendingPosition {
  marketId: string;
  walletAddress: string;
  collateralAmount: string;
  currentDebt: string;
  healthFactorWad: string | null;
  healthFactorReliable: boolean | null;
  /** Loan-token base units a liquidation credited to this wallet; claim via `claimLiquidationSurplus`. */
  liquidationSurplus: string;
  status: LendingPositionStatus;
  lastSyncedAt: string;
  /** The last refresh could not read the chain: values are the previous ones and may be out of date. */
  stale?: boolean;
  /** Operator diagnostic only. */
  lastSyncError?: string | null;
  /** The market's collateral balance is below its recorded total (forced move not yet reconciled). */
  collateralUnverified?: boolean;
}

export interface LendingSupplyPosition {
  marketId: string;
  walletAddress: string;
  currentClaim: string;
  lastSyncedAt: string;
}

// ── Repo Desk (bilateral sale-and-repurchase workflow) ──────────────────────

export type RepoRfqSide = 'BORROW_CASH' | 'LEND_CASH';
export type RepoRfqVisibility = 'TARGETED' | 'BROADCAST';
export type RepoRfqStatus = 'OPEN' | 'MATCHED' | 'CANCELLED' | 'EXPIRED';
export type RepoQuoteStatus = 'ACTIVE' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED' | 'SUPERSEDED';
export type RepoTradeStatus = 'PENDING_OPEN_SETTLEMENT' | 'OPEN' | 'MARGIN_CALL' | 'PENDING_CLOSE' | 'DISPUTED' | 'CLOSED' | 'DEFAULTED' | 'CANCELLED';
export type RepoDefaultGround = 'MARGIN_NOT_MET' | 'REPURCHASE_UNPAID' | 'COLLATERAL_RETURN_FAILURE';
export type RepoSubstitutionStatus = 'PENDING' | 'APPROVED' | 'COMPLETED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED';
export type RepoSettlementMethod = 'DVP' | 'FOP';

export interface RepoCounterparty { id: string; name: string; lei: string | null; }
export interface RepoCollateral {
  id: string; name: string; isin: string | null; assetNumber: string;
  heldQuantity: number; availableQuantity: number; maturityDate: string | null;
}
export interface RepoParticipation {
  participating: boolean; listed: boolean; optedInAt: string | null; eligibilityIssues: string[];
}
export interface RepoQuote {
  id: string; quotingEntityId: string; quotingEntityName: string; cashAmount: number;
  repoRate: number; haircutBps: number; validUntil: string; status: RepoQuoteStatus;
  message: string | null; createdAt: string; version: number; termsHash: string;
}
export interface RepoRfq {
  id: string; side: RepoRfqSide; visibility: RepoRfqVisibility; status: RepoRfqStatus;
  requesterEntityId: string; requesterName: string; collateralAssetId: string;
  collateralAssetName: string; collateralIsin: string | null; collateralQuantity: number;
  cashAmount: number; cashCurrency: string; startDate: string; endDate: string;
  proposedRepoRate: number | null; proposedHaircutBps: number | null;
  settlementMethod: RepoSettlementMethod; expiresAt: string; targetEntityIds: string[];
  notes: string | null; mine: boolean; canQuote: boolean; tradeId: string | null;
  quotes: RepoQuote[]; createdAt: string; updatedAt: string;
}
export interface RepoLifecycleEvent {
  id: string; type: string; actorEntityId: string | null; actorName: string | null; amount: number | null;
  assetId: string | null; quantity: number | null; reference: string | null;
  note: string | null; createdAt: string;
}
export interface RepoTrade {
  id: string; rfqId: string; acceptedQuoteId: string; status: RepoTradeStatus;
  cashBorrowerEntityId: string; cashBorrowerName: string; cashLenderEntityId: string;
  cashLenderName: string; collateralAssetId: string; collateralAssetName: string;
  collateralIsin: string | null; collateralQuantity: number; cashAmount: number;
  cashCurrency: string; repoRate: number; haircutBps: number; startDate: string;
  endDate: string; repurchaseAmount: number; settlementMethod: RepoSettlementMethod;
  openCashConfirmed: boolean; openCollateralConfirmed: boolean; closeCashConfirmed: boolean;
  closeCollateralConfirmed: boolean; marginCallAmount: number | null; marginCallDueAt: string | null;
  pendingSubstitutionAssetId: string | null; pendingSubstitutionQuantity: number | null;
  borrower: boolean; events: RepoLifecycleEvent[]; createdAt: string; updatedAt: string;
  termsHash: string; acceptedQuoteVersion: number | null; dayCountBasis: number; uti: string | null;
  venue: string | null; collateralReuseConsent: boolean;
  openCashDeclaredAt: string | null; openCollateralDeclaredAt: string | null;
  closeCashDeclaredAt: string | null; closeCollateralDeclaredAt: string | null;
  marginValuationReference: string | null; marginValuationAmount: number | null;
  marginHaircutBps: number | null; marginDeliveredAt: string | null;
  defaultNoticeAt: string | null; defaultNoticeGround: RepoDefaultGround | null;
  defaultGround: RepoDefaultGround | null; defaultingPartyEntityId: string | null;
  disputeReason: string | null; preDisputeStatus: RepoTradeStatus | null; disputedAt: string | null;
  substitutions: RepoSubstitution[];
}
export interface RepoSubstitution {
  id: string; assetId: string; quantity: number; status: RepoSubstitutionStatus; requestedBy: string;
  requestedAt: string | null; decidedAt: string | null; replacementReceivedAt: string | null;
  originalReturnedAt: string | null; completedAt: string | null; note: string | null;
}
export interface RepoSftrFields {
  uti: string | null; venue: string | null; lenderLei: string | null; borrowerLei: string | null; roles: string;
  currency: string; principalAmount: number; repoRatePercent: number; dayCountBasis: number;
  startDate: string; maturityDate: string; repurchaseAmount: number; collateralIsin: string | null;
  collateralQuantity: number; haircutBps: number; collateralReuseConsent: boolean; termsHash: string;
  status: RepoTradeStatus; notice: string;
}

// ─── Webhooks ───────────────────────────────────────────────────────────────

export type WebhookEventType =
  | 'KYC_APPROVED' | 'KYC_REJECTED'
  | 'ASSET_APPROVED' | 'ASSET_REJECTED'
  | 'SUBSCRIPTION_ORDER_ALLOCATED' | 'SUBSCRIPTION_ORDER_CONFIRMED' | 'SUBSCRIPTION_ORDER_REJECTED'
  | 'SUBSCRIPTION_ORDER_PAYMENT_CONFIRMED' | 'SUBSCRIPTION_ORDER_SETTLED' | 'SUBSCRIPTION_ORDER_LAPSED'
  | 'TRADE_EXECUTED' | 'TRADE_PAYMENT_CONFIRMED' | 'TRADE_PAYMENT_DISPUTED';

export interface WebhookSubscription {
  id: string;
  url: string;
  eventTypes: WebhookEventType[];
  enabled: boolean;
  createdAt: string;
  /** Set when the platform (not the owner) disabled it: URL_POLICY or CIRCUIT_BREAKER. */
  disabledReason?: 'URL_POLICY' | 'CIRCUIT_BREAKER' | string | null;
  secretRotatedAt?: string | null;
  /** Only populated in the response to the create / rotate-secret call, never shown again. */
  secret: string | null;
}

export type WebhookDeliveryStatus = 'PENDING' | 'SUCCESS' | 'FAILED';
/** Coarse by design: no HTTP status code or error text is exposed. */
export type WebhookDeliveryOutcome = 'OK' | 'RECEIVER_ERROR' | 'UNREACHABLE' | 'BLOCKED';

export interface WebhookDelivery {
  /** The delivery id sent in `X-Registerwerk-Delivery`. */
  id: string;
  /** Shared by all subscribers of the same event (`X-Registerwerk-Event-Id`); dedupe on it. */
  eventId: string;
  eventType: WebhookEventType;
  status: WebhookDeliveryStatus;
  outcome: WebhookDeliveryOutcome | null;
  attemptCount: number;
  lastAttemptedAt: string | null;
  /** When the next retry is due; null once delivered or given up. */
  nextAttemptAt: string | null;
  createdAt: string;
}
