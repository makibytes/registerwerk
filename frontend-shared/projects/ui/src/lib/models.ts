// ─── Address Endpoints ────────────────────────────────────────────────────────

export type EndpointOwnerType   = 'OPERATOR' | 'ENTITY';
export type EndpointAddressType = 'WALLET' | 'CONTRACT';
export type EndpointRiskLevel   = 'LOW' | 'MEDIUM' | 'HIGH';

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
