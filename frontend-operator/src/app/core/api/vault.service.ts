import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { VaultNavStrike, VaultRequest, VaultStateSummary } from '../models';

@Injectable({ providedIn: 'root' })
export class VaultService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}`;

  strikeNav(deploymentId: string, body: {
    navPerShare: number;
    effectiveAt: string;
    reportDocId?: string;
  }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/nav-strike`, body);
  }

  getNavStrikes(deploymentId: string): Observable<VaultNavStrike[]> {
    return this.http.get<VaultNavStrike[]>(`${this.base}/deployments/${deploymentId}/nav-strikes`);
  }

  getVaultRequests(deploymentId: string, status = 'PENDING'): Observable<VaultRequest[]> {
    const params = new HttpParams().set('status', status);
    return this.http.get<VaultRequest[]>(`${this.base}/deployments/${deploymentId}/vault-requests`, { params });
  }

  /** Confirmed vault state — the NAV a fulfilment settles at. */
  getVaultState(deploymentId: string): Observable<VaultStateSummary> {
    return this.http.get<VaultStateSummary>(`${this.base}/deployments/${deploymentId}/vault-state`);
  }

  /** Settles at the NAV currently struck on-chain; the executed NAV is recorded from the event. */
  fulfillRequest(deploymentId: string, requestId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/deployments/${deploymentId}/vault-requests/${requestId}/fulfill`,
      {}
    );
  }

  /** Registry force-cancel on a legal basis (step-up + 4-eyes: VAULT_REQUEST_FORCE_CANCEL). */
  forceCancelRequest(
    deploymentId: string,
    requestId: string,
    body: { to: string; legalBasis: string },
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<{ txId: string }> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<{ txId: string }>(
      `${this.base}/deployments/${deploymentId}/vault-requests/${requestId}/force-cancel`,
      body,
      { headers }
    );
  }

  cancelRequest(deploymentId: string, requestId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/deployments/${deploymentId}/vault-requests/${requestId}/cancel`,
      {}
    );
  }

  setDepositCap(deploymentId: string, cap: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/deposit-cap`, { cap });
  }
}
