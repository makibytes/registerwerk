import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AssetBondTerms } from '../models';

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

  saveBondTerms(assetId: string, terms: Partial<AssetBondTerms>): Observable<AssetBondTerms> {
    return this.http.post<AssetBondTerms>(`${this.base}/assets/${assetId}/bond-terms`, terms);
  }
}
