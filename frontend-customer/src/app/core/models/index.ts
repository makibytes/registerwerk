// ─── Enumerations ────────────────────────────────────────────────────────────

export type AssetStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'ISSUED'
  | 'SUSPENDED'
  | 'REDEEMED';

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

export type OnchainLevel = 'NONE' | 'SIMPLE' | 'CONTROL';

export type TokenStandard =
  | 'ERC20'
  | 'ERC721'
  | 'ERC1155'
  | 'ERC3643'
  | 'CONF_ERC20'
  | 'CONF_ERC3643'
  | 'SPL';

export type Chain = 'ETHEREUM' | 'POLYGON' | 'BASE' | 'SOLANA';

export type Network = 'MAINNET' | 'TESTNET';

export type DeploymentStatus = 'PENDING' | 'CONFIRMED' | 'FAILED';

export type KycStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type UserRole =
  | 'ISSUER'
  | 'INVESTOR'
  | 'COMPANY_ADMIN'
  | 'REGISTRY_ADMIN'
  | 'AUDITOR'
  | 'TRADER';

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
  issuerEntityId: string;
  jurisdiction: Jurisdiction | null;
  createdAt: string;
  updatedAt: string;
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
  role: UserRole;
  entityId: string;
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
  clientSecret: string;
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

// ─── Address Endpoints (re-exported from @registerwerk/ui) ────────────────────

export type {
  EndpointOwnerType,
  EndpointAddressType,
  EndpointRiskLevel,
  Endpoint,
  EndpointCreateRequest,
  EndpointUpdateRequest,
  AddressResolveResponse,
} from '@registerwerk/ui';
