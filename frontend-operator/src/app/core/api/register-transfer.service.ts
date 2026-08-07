import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { RegisterTransfer } from '../models';

/**
 * §§21/22 eWpG registry-operator handover — wraps `registertransfer.web.RegisterTransferController`,
 * which had no frontend caller at all: the initiate → export → onchain-handover → complete
 * lifecycle (the thing a bank's legal team asks about first when evaluating an exit) was
 * curl-only.
 */
@Injectable({ providedIn: 'root' })
export class RegisterTransferService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/register-transfers`;

  listForAsset(assetId: string): Observable<RegisterTransfer[]> {
    return this.http.get<RegisterTransfer[]>(`${this.base}/assets/${assetId}`);
  }

  initiate(assetId: string, successorName: string, successorIdentifier: string | undefined, reason: string, initiatedBy: string): Observable<RegisterTransfer> {
    return this.http.post<RegisterTransfer>(this.base, { assetId, successorName, successorIdentifier, reason, initiatedBy });
  }

  export(transferId: string): Observable<Blob> {
    return this.http.post(`${this.base}/${transferId}/export`, {}, { responseType: 'blob' });
  }

  recordOnchainHandover(transferId: string, txHash: string, stepUpToken: string, dualControlToken: string): Observable<RegisterTransfer> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<RegisterTransfer>(`${this.base}/${transferId}/onchain-handover`, { txHash }, { headers });
  }

  complete(transferId: string, stepUpToken: string, dualControlToken: string): Observable<RegisterTransfer> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<RegisterTransfer>(`${this.base}/${transferId}/complete`, {}, { headers });
  }

  cancel(transferId: string, reason: string): Observable<RegisterTransfer> {
    return this.http.post<RegisterTransfer>(`${this.base}/${transferId}/cancel`, { reason });
  }
}
