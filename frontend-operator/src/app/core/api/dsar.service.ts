import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
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

  /** Marks an erasure request as completed, recording the operator's resolution note. */
  complete(id: string, note: string): Observable<ErasureRequestView> {
    return this.http.post<ErasureRequestView>(`${this.base}/${id}/complete`, { note });
  }

  /** Rejects an erasure request (e.g. statutory retention still applies), with a note. */
  reject(id: string, note: string): Observable<ErasureRequestView> {
    return this.http.post<ErasureRequestView>(`${this.base}/${id}/reject`, { note });
  }
}
