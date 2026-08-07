// ─── Enumerations ────────────────────────────────────────────────────────────

export type AssetStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'ISSUED'
  | 'SUSPENDED'
  | 'REDEEMED';

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
  callSchedule: Record<string, unknown>[] | null;
  bondStatus: 'ACTIVE' | 'MATURED' | 'CALLED' | 'DEFAULTED' | 'REDEEMED';
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

export type SettlementStatus = 'PENDING' | 'SETTLED';

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

// ── Lending (repo/collateralized-lending isolated markets) ──────────────────
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
  marketAddress: string;
  vaultAddress: string | null;
  collateralAssetId: string | null;
  collateralAssetName: string | null;
  collateralIsin: string | null;
  collateralTokenAddress: string;
  loanTokenAddress: string;
  loanRailCode: string | null;
  lltvBps: number;
  liquidationBonusBps: number;
  baseRateWad: string;
  slopeWad: string;
  priceOracleAddress: string;
  status: LendingMarketStatus;
  jurisdiction: Jurisdiction | null;
  micarApplicable: boolean | null;
  defiInteropModel: 'NONE' | 'NOMINEE_POOL' | 'ORACLE_ONLY' | null;
  createdAt: string;
}

export interface LendingQuote {
  marketId: string;
  collateralAmount: string;
  pricePerUnit: string;
  priceUpdatedAt: string;
  maxBorrowAmount: string;
  lltvBps: number;
  utilizationWad: string;
  borrowRateWad: string;
}

export interface LendingPosition {
  marketId: string;
  walletAddress: string;
  collateralAmount: string;
  currentDebt: string;
  healthFactorWad: string | null;
  status: LendingPositionStatus;
  lastSyncedAt: string;
}

export interface LendingSupplyPosition {
  marketId: string;
  walletAddress: string;
  currentClaim: string;
  lastSyncedAt: string;
}
