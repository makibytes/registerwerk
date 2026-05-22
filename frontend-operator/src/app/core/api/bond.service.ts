import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AssetBondTerms, AssetCouponPayment } from '../models';

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

  payCoupon(deploymentId: string, body: {
    scheduledDate: string;
    paidDate: string;
    amountPerUnit: number;
    slotId?: string;
  }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/coupon-payment`, body);
  }

  redeem(deploymentId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/redeem`, {});
  }

  earlyCall(deploymentId: string, body: { callDate: string; callPrice: number }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/early-call`, body);
  }

  fixFloatingRate(deploymentId: string, body: { rate: number; fixingDate: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/rate-fixing`, body);
  }

  getCouponPayments(assetId: string): Observable<AssetCouponPayment[]> {
    return this.http.get<AssetCouponPayment[]>(`${this.base}/assets/${assetId}/coupon-payments`);
  }
}
