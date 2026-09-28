import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AssetBondTerms, CouponScheduleEntry } from '../models';

/**
 * Wraps `asset.web.BondTermsController` (`GET /api/v1/assets/{assetId}/bond-terms`), which
 * was REGISTRY_ADMIN-only end to end — an issuer or investor could never see the terms of the
 * bond they issued or hold. GET is now also readable by the asset's issuer and its current
 * holders (backend `@PreAuthorize` on `getBondTerms`); write stays operator-only.
 */
@Injectable({ providedIn: 'root' })
export class BondTermsService {
  private readonly http = inject(HttpClient);

  /** Returns null (not an error toast) when the asset has no bond terms — most assets don't. */
  getBondTerms(assetId: string): Observable<AssetBondTerms> {
    return this.http.get<AssetBondTerms>(`${environment.apiUrl}/assets/${assetId}/bond-terms`);
  }

  /** Generated coupon schedule — same readers as the terms (issuer, holders, operator). */
  getCouponSchedule(assetId: string): Observable<CouponScheduleEntry[]> {
    return this.http.get<CouponScheduleEntry[]>(`${environment.apiUrl}/assets/${assetId}/bond-terms/schedule`);
  }
}
