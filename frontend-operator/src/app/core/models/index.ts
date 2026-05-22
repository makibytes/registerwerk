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
  entityId: string;
  name: string;
  effectiveFrom: string;
  effectiveTo?: string;
  changedBy?: string;
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
