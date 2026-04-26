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
  tokenStandard: 'ERC20' | 'ERC721' | 'ERC1155' | 'ERC3643' | 'CONF_ERC20' | 'CONF_ERC3643' | 'SPL';
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

export type SyncStatus = 'READY' | 'PENDING' | 'UPDATING';

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
