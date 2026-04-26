import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface Erc3643Suite {
  id: string;
  assetDeploymentId: string;
  tokenAddress: string;
  identityRegistryAddress: string;
  identityRegistryStorage: string;
  complianceAddress: string;
  claimTopicsRegistry: string;
  trustedIssuersRegistry: string;
  isConfidential: boolean;
  createdAt: string;
}

export interface IdentityRegistryEntry {
  id: string;
  suiteId: string;
  walletAddress: string;
  onchainIdentityId: string;
  identityAddress: string | null;
  legalEntityId: string | null;
  entityName: string | null;
  countryCode: number | null;
  registeredAt: string;
  registeredByTx: string | null;
  syncStatus: 'READY' | 'PENDING' | 'UPDATING';
  active: boolean;
  verified: boolean;
}

export interface ComplianceModuleInfo {
  id: string;
  moduleAddress: string;
  moduleType: string;
  maxInvestors: number | null;
  maxBalance: string | null;
  transferCooldown: number | null;
  blockedCountries: number[];
}

export interface ComplianceStatus {
  suiteId: string;
  investorCount: number;
  maxInvestors: number | null;
  maxBalance: string | null;
  transferCooldown: number | null;
  blockedCountries: number[];
  modules: ComplianceModuleInfo[];
}

@Injectable({ providedIn: 'root' })
export class Erc3643Service {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  getSuite(assetId: string, deploymentId: string): Observable<Erc3643Suite> {
    return this.http.get<Erc3643Suite>(
      `${this.base}/assets/${assetId}/erc3643/${deploymentId}/suite`
    );
  }

  getIdentityRegistry(assetId: string, deploymentId: string): Observable<IdentityRegistryEntry[]> {
    return this.http.get<IdentityRegistryEntry[]>(
      `${this.base}/assets/${assetId}/erc3643/${deploymentId}/identity-registry`
    );
  }

  getComplianceStatus(assetId: string, deploymentId: string): Observable<ComplianceStatus> {
    return this.http.get<ComplianceStatus>(
      `${this.base}/assets/${assetId}/erc3643/${deploymentId}/compliance-status`
    );
  }
}
