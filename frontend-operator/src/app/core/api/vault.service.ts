import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { VaultNavStrike, VaultRequest } from '../models';

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

  fulfillRequest(deploymentId: string, requestId: string, navAtFulfill: number): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/deployments/${deploymentId}/vault-requests/${requestId}/fulfill`,
      { navAtFulfill }
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
