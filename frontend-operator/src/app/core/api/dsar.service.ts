import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ErasureRequestView } from '../models';

@Injectable({ providedIn: 'root' })
export class DsarService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/dsar/erasure-requests`;

  /** Open (REQUESTED / IN_REVIEW) DSGVO Art. 17 erasure requests, oldest first. */
  listOpen(): Observable<ErasureRequestView[]> {
    return this.http.get<ErasureRequestView[]>(this.base);
  }

  /**
   * Marks an erasure request as completed, irreversibly tombstoning the entity's user PII.
   * Dual control: requires both the initiator's step-up token and a
   * second approver's dual-control token.
   */
  complete(
    id: string,
    note: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<ErasureRequestView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<ErasureRequestView>(`${this.base}/${id}/complete`, { note }, { headers });
  }

  /** Rejects an erasure request (e.g. statutory retention still applies), with a note. */
  reject(id: string, note: string, stepUpToken: string): Observable<ErasureRequestView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<ErasureRequestView>(`${this.base}/${id}/reject`, { note }, { headers });
  }
}
