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

export interface Asset {
  id: string;
  assetNumber: string;
  issuerId: string;
  issuerName?: string;
  name: string;
  isin?: string;
  tokenStandard: 'ERC20' | 'ERC721' | 'ERC1155' | 'ERC3643' | 'CONF_ERC20' | 'CONF_ERC3643' | 'SPL';
  onchainLevel: 'NONE' | 'SIMPLE' | 'CONTROL';
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'ISSUED' | 'SUSPENDED' | 'REDEEMED';
  totalSupply?: number;
  decimals?: number;
  createdAt: string;
  updatedAt?: string;
}

export interface AssetDeployment {
  id: string;
  assetId: string;
  chain: 'ETHEREUM' | 'POLYGON' | 'BASE' | 'SOLANA';
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
