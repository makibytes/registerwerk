import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CorporateAction } from '../models';

@Injectable({ providedIn: 'root' })
export class CorporateActionsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/corporate-actions`;

  downloadPositionStatement(entityId: string): Observable<Blob> {
    return this.http.get(
      `${environment.apiUrl}/customers/${entityId}/statements`,
      { responseType: 'blob' },
    );
  }

  downloadTaxCertificate(entityId: string, year: number): Observable<Blob> {
    return this.http.get(
      `${environment.apiUrl}/customers/${entityId}/tax-certificates/${year}`,
      { responseType: 'blob' },
    );
  }

  listForAsset(assetId: string): Observable<CorporateAction[]> {
    return this.http.get<CorporateAction[]>(this.base, { params: { assetId } });
  }

  /**
   * Dual-control (Vieraugenprinzip) settlement approval — the approver must be a
   * different actor than whoever initiated the action, enforced by the backend.
   */
  approveSettlement(corporateActionId: string, stepUpToken: string, dualControlToken?: string): Observable<CorporateAction> {
    let headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    if (dualControlToken) {
      headers = headers.set('X-Dual-Control-Token', dualControlToken);
    }
    return this.http.post<CorporateAction>(`${this.base}/${corporateActionId}/approve-settlement`, {}, { headers });
  }

  downloadConfirmation(corporateActionId: string): Observable<Blob> {
    return this.http.get(`${this.base}/${corporateActionId}/confirmation`, { responseType: 'blob' });
  }

  downloadIso20022Confirmation(corporateActionId: string): Observable<Blob> {
    return this.http.get(`${this.base}/${corporateActionId}/confirmation/iso20022`, { responseType: 'blob' });
  }
}
