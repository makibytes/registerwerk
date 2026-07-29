import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, of } from 'rxjs';
import { environment } from '../../../environments/environment';

export type GasSponsor = 'OPERATOR' | 'ISSUER';

export interface GasSponsorshipPolicy {
  id: string;
  assetDeploymentId: string | null;
  issuerId: string | null;
  sponsor: GasSponsor;
  monthlyCapEth: string | null;
  active: boolean;
  createdAt: string;
}

export interface GasSponsorshipCreateRequest {
  sponsor: GasSponsor;
  monthlyCapEth?: string | number;
}

/**
 * Configures who pays gas for a customer's sponsored (ERC-4337) transactions — a
 * per-deployment override, or an issuer-level default new deployments inherit
 * (see docs/platform/account-abstraction.md and EwpgPaymaster.sol).
 */
@Injectable({ providedIn: 'root' })
export class GasSponsorshipService {
  private readonly http = inject(HttpClient);

  /** The policy actually in effect for this deployment (override, else issuer default), or null if unconfigured. */
  getEffectivePolicy(assetId: string, depId: string): Observable<GasSponsorshipPolicy | null> {
    return this.http
      .get<GasSponsorshipPolicy>(`${environment.apiUrl}/assets/${assetId}/deployments/${depId}/gas-sponsorship`)
      .pipe(catchError(() => of(null)));
  }

  createForDeployment(assetId: string, depId: string, body: GasSponsorshipCreateRequest): Observable<GasSponsorshipPolicy> {
    return this.http.post<GasSponsorshipPolicy>(
      `${environment.apiUrl}/assets/${assetId}/deployments/${depId}/gas-sponsorship`, body);
  }

  deactivate(policyId: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiUrl}/gas-sponsorship/${policyId}`);
  }

  listForIssuer(entityId: string): Observable<GasSponsorshipPolicy[]> {
    return this.http.get<GasSponsorshipPolicy[]>(`${environment.apiUrl}/entities/${entityId}/gas-sponsorship-policies`);
  }

  createIssuerDefault(entityId: string, body: GasSponsorshipCreateRequest): Observable<GasSponsorshipPolicy> {
    return this.http.post<GasSponsorshipPolicy>(
      `${environment.apiUrl}/entities/${entityId}/gas-sponsorship-default`, body);
  }
}
