import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TxSubmissionResponse } from './transaction.service';

/**
 * Wraps `blockchain.web.SolanaTokenAdminController` (`/solana-admin`), the controller that
 * finally wires up `SolanaTokenAdminService` — previously "Phase-4" and unreachable from any
 * API despite being a fully implemented Token-2022 Permanent Delegate integration.
 */
@Injectable({ providedIn: 'root' })
export class SolanaAdminService {
  private readonly http = inject(HttpClient);

  private base(assetId: string, depId: string): string {
    return `${environment.apiUrl}/assets/${assetId}/deployments/${depId}/solana-admin`;
  }

  forcedTransfer(
    assetId: string, depId: string,
    body: { fromTokenAccount: string; toTokenAccount: string; amount: string; decimals: number; legalBasis: string },
    stepUpToken: string, dualControlToken: string,
  ): Observable<TxSubmissionResponse> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<TxSubmissionResponse>(`${this.base(assetId, depId)}/forced-transfer`, body, { headers });
  }

  forceBurn(
    assetId: string, depId: string,
    body: { tokenAccount: string; amount: string; decimals: number; legalBasis: string },
    stepUpToken: string, dualControlToken: string,
  ): Observable<TxSubmissionResponse> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<TxSubmissionResponse>(`${this.base(assetId, depId)}/force-burn`, body, { headers });
  }

  freeze(assetId: string, depId: string, tokenAccount: string): Observable<TxSubmissionResponse> {
    return this.http.post<TxSubmissionResponse>(`${this.base(assetId, depId)}/freeze`, { tokenAccount });
  }

  thaw(assetId: string, depId: string, tokenAccount: string): Observable<TxSubmissionResponse> {
    return this.http.post<TxSubmissionResponse>(`${this.base(assetId, depId)}/thaw`, { tokenAccount });
  }
}
