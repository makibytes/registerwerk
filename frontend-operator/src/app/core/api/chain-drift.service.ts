import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ChainDriftEvent, PageResponse } from '../models';

@Injectable({ providedIn: 'root' })
export class ChainDriftService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/chain-drift`;

  list(status: 'OPEN' | 'RESOLVED' = 'OPEN', page = 0, size = 50): Observable<PageResponse<ChainDriftEvent>> {
    const params = new HttpParams().set('status', status).set('page', page).set('size', size);
    return this.http.get<PageResponse<ChainDriftEvent>>(this.base, { params });
  }

  openCount(): Observable<number> {
    return this.http.get<number>(`${this.base}/open-count`);
  }

  get(id: string): Observable<ChainDriftEvent> {
    return this.http.get<ChainDriftEvent>(`${this.base}/${id}`);
  }

  resolve(id: string, notes: string): Observable<ChainDriftEvent> {
    return this.http.post<ChainDriftEvent>(`${this.base}/${id}/resolve`, { notes });
  }
}
