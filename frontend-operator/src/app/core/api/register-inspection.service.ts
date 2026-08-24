import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { RegisterInspectionRequest } from '../models';

/** Raw Spring Data `Page<T>` JSON shape — this endpoint returns `Page` directly, not the
 *  project's `shared.api.PageResponse` wrapper (which uses `page` instead of `number`). */
export interface SpringPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * §10 eWpG register inspection — wraps `registertransfer.web.RegisterInspectionController`,
 * which had no operator frontend caller: approve/reject/fulfil were curl-only despite carrying
 * the statutory decision on a customer's inspection right.
 */
@Injectable({ providedIn: 'root' })
export class RegisterInspectionService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/register-inspections`;

  listForAsset(assetId: string, page = 0, size = 20): Observable<SpringPage<RegisterInspectionRequest>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<SpringPage<RegisterInspectionRequest>>(`${this.base}/assets/${assetId}`, { params });
  }

  approve(requestId: string, operatorEntityId: string, reason: string): Observable<RegisterInspectionRequest> {
    return this.http.post<RegisterInspectionRequest>(`${this.base}/${requestId}/approve`, { operatorEntityId, reason });
  }

  reject(requestId: string, operatorEntityId: string, reason: string): Observable<RegisterInspectionRequest> {
    return this.http.post<RegisterInspectionRequest>(`${this.base}/${requestId}/reject`, { operatorEntityId, reason });
  }

  fulfil(requestId: string): Observable<Blob> {
    return this.http.post(`${this.base}/${requestId}/fulfil`, {}, { responseType: 'blob' });
  }
}
