import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Asset, AssetBondTerms, CouponScheduleEntry, TermsAmendment } from '../models';

/**
 * Bond terms CRUD. Lifecycle servicing (coupon payments, redemption, early call, rate
 * fixing) runs through the automated corporate-actions pipeline instead of a direct
 * per-action endpoint — see CorporateActionAdminController / CorporateActionService
 * on the backend and the Corporate Actions screen in this app.
 */
@Injectable({ providedIn: 'root' })
export class BondService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}`;

  getBondTerms(assetId: string): Observable<AssetBondTerms> {
    return this.http.get<AssetBondTerms>(`${this.base}/assets/${assetId}/bond-terms`);
  }

  /** Allowed until the asset is issued (step-up, reason BOND_TERMS_UPSERT); afterwards 409 → amend instead. */
  saveBondTerms(assetId: string, terms: Partial<AssetBondTerms>, stepUpToken: string): Observable<AssetBondTerms> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<AssetBondTerms>(`${this.base}/assets/${assetId}/bond-terms`, terms, { headers });
  }

  getCouponSchedule(assetId: string): Observable<CouponScheduleEntry[]> {
    return this.http.get<CouponScheduleEntry[]>(`${this.base}/assets/${assetId}/bond-terms/schedule`);
  }

  /** Post-approval economic-terms amendment: step-up + second approver (reason TERMS_AMENDMENT). */
  amendTerms(assetId: string, body: TermsAmendment, stepUpToken: string, dualControlToken: string): Observable<Asset> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}`, 'X-Dual-Control-Token': dualControlToken });
    return this.http.post<Asset>(`${this.base}/assets/${assetId}/terms-amendments`, body, { headers });
  }
}
