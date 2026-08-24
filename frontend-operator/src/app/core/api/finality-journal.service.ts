import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ChainEffectView } from '../models';

export interface RetryResultResponse {
  outcome: 'COMPENSATED' | 'NOT_APPLICABLE' | 'FAILED' | 'IRREVERSIBLE';
  detail: string | null;
}

@Injectable({ providedIn: 'root' })
export class FinalityJournalService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/finality-journal`;

  listUnresolved(): Observable<ChainEffectView[]> {
    return this.http.get<ChainEffectView[]>(`${this.base}/unresolved`);
  }

  retry(chainEffectId: string, stepUpToken: string): Observable<RetryResultResponse> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<RetryResultResponse>(`${this.base}/${chainEffectId}/retry`, {}, { headers });
  }

  acknowledge(chainEffectId: string, reason: string, stepUpToken: string): Observable<ChainEffectView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<ChainEffectView>(`${this.base}/${chainEffectId}/acknowledge`, { reason }, { headers });
  }
}
