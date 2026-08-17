import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { IndexerStateResponse } from '../models';

/**
 * Operator visibility and recovery surface for `indexer_state` (`IndexerAdminController`).
 * Read access is REGISTRY_ADMIN/COMPLIANCE_OFFICER/AUDIT; {@link reset} is REGISTRY_ADMIN-only
 * (also enforced server-side) — previously a stuck ERROR indexer had no application-level path
 * back to ACTIVE, only a manual SQL update.
 */
@Injectable({ providedIn: 'root' })
export class IndexerAdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/indexers`;

  list(): Observable<IndexerStateResponse[]> {
    return this.http.get<IndexerStateResponse[]>(this.base);
  }

  /**
   * Clears a stuck indexer's error state so it resumes on its next scheduled tick.
   * `fullResync=true` also discards the existing cursor, forcing a full re-sync from genesis —
   * a much heavier, slower operation that should only be used when the existing cursor itself is
   * suspect (not just a transient error streak).
   */
  reset(id: string, fullResync: boolean): Observable<IndexerStateResponse> {
    const params = new HttpParams().set('fullResync', fullResync);
    return this.http.post<IndexerStateResponse>(`${this.base}/${id}/reset`, {}, { params });
  }
}
