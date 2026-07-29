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
  bondStatus: 'ACTIVE' | 'MATURED' | 'CALLED' | 'DEFAULTED' | 'REDEEMED';
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
  assetAmount?: string;
  shareAmount?: string;
  requestStatus: 'PENDING' | 'FULFILLED' | 'CANCELLED';
  requestedAt: string;
  fulfilledAt?: string;
  navAtFulfill?: number;
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

// ── Coupon payment ────────────────────────────────────────────────────────────
export interface AssetCouponPayment {
  id: string;
  assetId: string;
  slotId?: string;
  periodNo: number;
  scheduledDate: string;
  paidDate?: string;
  amountPerUnit?: number;
  couponStatus: 'SCHEDULED' | 'PAID' | 'MISSED';
  txRef?: string;
}

// ── Corporate actions ──────────────────────────────────────────────────────────
export type CorporateActionType =
  | 'COUPON' | 'DIVIDEND' | 'SPLIT' | 'REVERSE_SPLIT' | 'CONVERSION'
  | 'REDEMPTION' | 'PARTIAL_REDEMPTION' | 'PLEDGE' | 'CALL'
  | 'CAPITAL_CALL' | 'INTEREST_PAYMENT';

export type CorporateActionStatus =
  | 'ANNOUNCED' | 'RECORD_DATE_SET' | 'COMPUTED' | 'AWAITING_SETTLEMENT' | 'SETTLED' | 'CLOSED' | 'CANCELLED';

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
  dualControlApproverId?: string;
  dualControlApprovedAt?: string;
  notes?: string;
  createdAt: string;
  updatedAt: string;
}

export interface LegalEntity {
  id: string;
  entityNumber: string;
  type: 'ISSUER' | 'INVESTOR' | 'AUDITOR';
  status: 'PENDING_ONBOARDING' | 'ACTIVE' | 'SUSPENDED' | 'DISSOLVED';
  currentName: string;
  leiCode?: string;
  registrationNumber?: string;
  registrationCountry?: string;
  kycStatus: 'NOT_STARTED' | 'IN_PROGRESS' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  createdAt: string;
  updatedAt?: string;
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
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'ISSUED' | 'SUSPENDED' | 'REDEEMED';
  jurisdiction?: Jurisdiction;
  totalSupply?: number;
  decimals?: number;
  createdAt: string;
  updatedAt?: string;
  hasTermSheet: boolean;
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

export interface AssetHolder {
  address: string;
  balance: number;
  percentage: number;
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
  valid: boolean;
  rowsChecked: number;
  firstBrokenSequenceNo?: number | null;
  checkedAt: string;
}

export interface OnboardingToken {
  token: string;
  entityId: string;
  expiresAt: string;
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
  balance: number | null;
  error: string | null;
}

export interface OperatorWallet {
  id: string;
  name: string;
  type: 'EVM' | 'SOLANA' | 'CANTON';
  address: string;
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
  roles: Array<'ISSUER' | 'INVESTOR' | 'AUDITOR'>;
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
}

// ─── Holder Blocks (§16 eWpG Sperrvermerk) ───────────────────────────────────

export type BlockType =
  | 'PFANDRECHT' | 'PFAENDUNG' | 'GERICHTSBESCHLUSS' | 'NACHLASSSPERRE'
  | 'VERFUGUNGSVERBOT' | 'INSOLVENZ' | 'TOD' | 'REGULATORISCH';

export type BlockStatus = 'ACTIVE' | 'LIFTED' | 'EXPIRED' | 'SUPERSEDED';

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
}

export type ProviderCriticality = 'STANDARD' | 'IMPORTANT' | 'CRITICAL';

export interface ThirdPartyProvider {
  id: string;
  name: string;
  category: string;
  criticality: ProviderCriticality;
  country: string | null;
  contractEnd: string | null;
  notifiedAuthority: boolean;
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
