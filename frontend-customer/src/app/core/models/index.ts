// ─── Enumerations ────────────────────────────────────────────────────────────

export type AssetStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'ISSUED'
  | 'REJECTED'
  | 'REVOKED';

export type OnchainLevel = 'LEVEL_0' | 'LEVEL_1' | 'LEVEL_2' | 'LEVEL_3';

export type TokenStandard =
  | 'ERC20'
  | 'ERC721'
  | 'ERC1155'
  | 'ERC1400'
  | 'ERC3643'
  | 'CONF_ERC20'
  | 'CONF_ERC3643'
  | 'SPL';

export type Chain = 'ETHEREUM' | 'POLYGON' | 'BASE' | 'SOLANA';

export type Network = 'MAINNET' | 'TESTNET';

export type DeploymentStatus = 'PENDING' | 'DEPLOYED' | 'FAILED';

export type KycStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type UserRole =
  | 'ISSUER'
  | 'INVESTOR'
  | 'COMPANY_ADMIN'
  | 'REGISTRY_ADMIN'
  | 'AUDITOR';

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
  createdAt: string;
  updatedAt: string;
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

// ─── Page / Query Params ──────────────────────────────────────────────────────

export interface PageParams {
  page?: number;
  size?: number;
  sort?: string;
  [key: string]: string | number | boolean | undefined;
}
