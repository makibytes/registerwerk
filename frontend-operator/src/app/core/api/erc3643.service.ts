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
  identityAddress: string;
  legalEntityId: string;
  entityName: string;
  countryCode: number | null;
  registeredAt: string;
  registeredByTx: string | null;
  syncStatus: 'READY' | 'PENDING' | 'UPDATING';
  active: boolean;
  verified: boolean;
}

export interface ComplianceStatus {
  suiteId: string;
  investorCount: number;
  maxInvestors: number | null;
  maxBalance: string | null;
  transferCooldown: number | null;
  blockedCountries: number[];
  modules: { id: string; moduleAddress: string; moduleType: string; active: boolean }[];
}

export interface TrustedIssuer {
  id: string;
  suiteId: string;
  issuerAddress: string;
  claimTopics: number[];
  legalEntityId: string | null;
  addedAt: string;
  removedAt: string | null;
}

export interface ClaimTopic {
  id: string;
  suiteId: string;
  topic: number;
  label: string;
}

@Injectable({ providedIn: 'root' })
export class Erc3643Service {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/assets`;

  private suiteUrl(assetId: string, deploymentId: string): string {
    return `${this.base}/${assetId}/erc3643/${deploymentId}`;
  }

  getSuite(assetId: string, deploymentId: string): Observable<Erc3643Suite> {
    return this.http.get<Erc3643Suite>(`${this.suiteUrl(assetId, deploymentId)}/suite`);
  }

  getIdentityRegistry(assetId: string, deploymentId: string): Observable<IdentityRegistryEntry[]> {
    return this.http.get<IdentityRegistryEntry[]>(`${this.suiteUrl(assetId, deploymentId)}/identity-registry`);
  }

  registerInvestor(assetId: string, deploymentId: string, body: {
    walletAddress: string; legalEntityId: string; chainConfigId: string; countryCode?: number;
  }): Observable<IdentityRegistryEntry> {
    return this.http.post<IdentityRegistryEntry>(`${this.suiteUrl(assetId, deploymentId)}/identity-registry`, body);
  }

  removeInvestor(assetId: string, deploymentId: string, entryId: string): Observable<void> {
    return this.http.delete<void>(`${this.suiteUrl(assetId, deploymentId)}/identity-registry/${entryId}`);
  }

  getComplianceStatus(assetId: string, deploymentId: string): Observable<ComplianceStatus> {
    return this.http.get<ComplianceStatus>(`${this.suiteUrl(assetId, deploymentId)}/compliance-status`);
  }

  addTrustedIssuer(assetId: string, deploymentId: string, body: {
    issuerAddress: string; claimTopics: number[]; legalEntityId?: string;
  }): Observable<void> {
    return this.http.post<void>(`${this.suiteUrl(assetId, deploymentId)}/trusted-issuers`, body);
  }

  removeTrustedIssuer(assetId: string, deploymentId: string, issuerId: string): Observable<void> {
    return this.http.delete<void>(`${this.suiteUrl(assetId, deploymentId)}/trusted-issuers/${issuerId}`);
  }

  getTrustedIssuers(assetId: string, deploymentId: string): Observable<TrustedIssuer[]> {
    return this.http.get<TrustedIssuer[]>(`${this.suiteUrl(assetId, deploymentId)}/trusted-issuers`);
  }

  addClaimTopic(assetId: string, deploymentId: string, body: { topic: number; label?: string }): Observable<void> {
    return this.http.post<void>(`${this.suiteUrl(assetId, deploymentId)}/claim-topics`, body);
  }

  getClaimTopics(assetId: string, deploymentId: string): Observable<ClaimTopic[]> {
    return this.http.get<ClaimTopic[]>(`${this.suiteUrl(assetId, deploymentId)}/claim-topics`);
  }

  freezeAddress(assetId: string, deploymentId: string, address: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/freeze`, { address });
  }

  unfreezeAddress(assetId: string, deploymentId: string, address: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/unfreeze`, { address });
  }

  pause(assetId: string, deploymentId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/pause`, {});
  }

  unpause(assetId: string, deploymentId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/unpause`, {});
  }

  forcedTransfer(assetId: string, deploymentId: string, body: {
    from: string; to: string; amount: string; reason?: string;
  }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/forced-transfer`, body);
  }

  forcedApprove(assetId: string, deploymentId: string, body: {
    owner: string; spender: string; amount: string; reason?: string;
  }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/forced-approve`, body);
  }

  forceBurn(assetId: string, deploymentId: string, body: {
    from: string; amount: string; legalBasis?: string;
  }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/force-burn`, body);
  }

  freezePartial(assetId: string, deploymentId: string, body: { address: string; amount: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/freeze-partial`, body);
  }

  unfreezePartial(assetId: string, deploymentId: string, body: { address: string; amount: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/unfreeze-partial`, body);
  }

  batchForcedTransfer(assetId: string, deploymentId: string, body: {
    froms: string[]; tos: string[]; amounts: string[];
  }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/batch-forced-transfer`, body);
  }

  batchMint(assetId: string, deploymentId: string, body: { addresses: string[]; amounts: string[] }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/batch-mint`, body);
  }

  batchBurn(assetId: string, deploymentId: string, body: { addresses: string[]; amounts: string[] }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.suiteUrl(assetId, deploymentId)}/batch-burn`, body);
  }
}
