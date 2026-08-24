import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface DsarExport {
  entityId?: string;
  entityNumber?: string;
  registrationNumber?: string;
  registrationCountry?: string;
  kycStatus?: string;
  kycExpiryDate?: string | null;
  gdprBasis?: string;
  retentionNote?: string;
  message?: string;
  [key: string]: unknown;
}

export interface DsarErasureResult {
  status: string;
  erasureRequestId?: string;
  entityId?: string;
  message: string;
  gdprBasis?: string;
  dueAt?: string;
  processingTime?: string;
}

/**
 * GDPR self-service — wraps `customer.web.DsarController`
 * (`GET /api/v1/me/dsar/export`, `POST /api/v1/me/dsar/erasure`), which had no frontend
 * caller: the customer portal had no way for a client to exercise their DSGVO Art. 15/17/20
 * rights despite the backend already implementing them.
 */
@Injectable({ providedIn: 'root' })
export class DsarService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/me/dsar`;

  exportMyData(): Observable<DsarExport> {
    return this.http.get<DsarExport>(`${this.base}/export`);
  }

  requestErasure(): Observable<DsarErasureResult> {
    return this.http.post<DsarErasureResult>(`${this.base}/erasure`, {});
  }
}
