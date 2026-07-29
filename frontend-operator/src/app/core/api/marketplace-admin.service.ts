import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  DappListingView,
  DappVersionView,
  ReviewDetailView,
  ReviewQueueItemView,
} from '../models';

@Injectable({ providedIn: 'root' })
export class MarketplaceAdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/marketplace`;

  reviewQueue(): Observable<ReviewQueueItemView[]> {
    return this.http.get<ReviewQueueItemView[]>(`${this.base}/review-queue`);
  }

  reviewDetail(versionId: string): Observable<ReviewDetailView> {
    return this.http.get<ReviewDetailView>(`${this.base}/review/${versionId}`);
  }

  startReview(versionId: string): Observable<DappVersionView> {
    return this.http.post<DappVersionView>(`${this.base}/review/${versionId}/start`, {});
  }

  approve(
    versionId: string,
    notes: string | null,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<DappVersionView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<DappVersionView>(`${this.base}/review/${versionId}/approve`, { notes }, { headers });
  }

  reject(versionId: string, reason: string, stepUpToken: string): Observable<DappVersionView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<DappVersionView>(`${this.base}/review/${versionId}/reject`, { reason }, { headers });
  }

  allListings(): Observable<DappListingView[]> {
    return this.http.get<DappListingView[]>(`${this.base}/admin/listings`);
  }

  deprecate(listingId: string, stepUpToken: string): Observable<DappListingView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<DappListingView>(`${this.base}/listings/${listingId}/deprecate`, {}, { headers });
  }

  delist(listingId: string, stepUpToken: string): Observable<DappListingView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<DappListingView>(`${this.base}/listings/${listingId}/delist`, {}, { headers });
  }
}
