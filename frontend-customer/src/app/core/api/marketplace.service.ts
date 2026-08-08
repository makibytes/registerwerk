import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CatalogCardView,
  CatalogDetailView,
  DappListingView,
  DappRequiredPermissionView,
  DappVersionView,
  ManifestValidationView,
  PageResponse,
  PaymentMethodView,
  PaymentRailView,
} from '../models';

@Injectable({ providedIn: 'root' })
export class MarketplaceService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/marketplace`;

  // ── Catalog (all authenticated users) ─────────────────────────────────────

  browse(params: { query?: string; category?: string; page?: number; size?: number } = {}):
      Observable<PageResponse<CatalogCardView>> {
    let httpParams = new HttpParams()
      .set('page', params.page ?? 0)
      .set('size', params.size ?? 24);
    if (params.query) httpParams = httpParams.set('query', params.query);
    if (params.category) httpParams = httpParams.set('category', params.category);
    return this.http.get<PageResponse<CatalogCardView>>(`${this.base}/catalog`, { params: httpParams });
  }

  detail(slug: string): Observable<CatalogDetailView> {
    return this.http.get<CatalogDetailView>(`${this.base}/catalog/${slug}`);
  }

  categories(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/catalog/categories`);
  }

  /** Enabled payment rails the registry provides — the reference list publishers pick codes from. */
  paymentRails(): Observable<PaymentRailView[]> {
    return this.http.get<PaymentRailView[]>(`${environment.apiUrl}/payment-rails/catalog`);
  }

  // ── Publisher console ─────────────────────────────────────────────────────

  createListing(body: {
    slug: string;
    name: string;
    category?: string;
    chainConfigId: string;
  }): Observable<DappListingView> {
    return this.http.post<DappListingView>(`${this.base}/listings`, body);
  }

  myListings(): Observable<DappListingView[]> {
    return this.http.get<DappListingView[]>(`${this.base}/my-listings`);
  }

  getListing(listingId: string): Observable<DappListingView> {
    return this.http.get<DappListingView>(`${this.base}/listings/${listingId}`);
  }

  versions(listingId: string): Observable<DappVersionView[]> {
    return this.http.get<DappVersionView[]>(`${this.base}/listings/${listingId}/versions`);
  }

  createVersion(listingId: string): Observable<DappVersionView> {
    return this.http.post<DappVersionView>(`${this.base}/listings/${listingId}/versions`, {});
  }

  putManifest(listingId: string, versionId: string, manifestRaw: string): Observable<ManifestValidationView> {
    const headers = new HttpHeaders({ 'Content-Type': 'application/json' });
    return this.http.put<ManifestValidationView>(
      `${this.base}/listings/${listingId}/versions/${versionId}/manifest`,
      manifestRaw,
      { headers },
    );
  }

  manifest(listingId: string, versionId: string): Observable<{ manifestRaw: string | null }> {
    return this.http.get<{ manifestRaw: string | null }>(
      `${this.base}/listings/${listingId}/versions/${versionId}/manifest`,
    );
  }

  versionPermissions(listingId: string, versionId: string): Observable<DappRequiredPermissionView[]> {
    return this.http.get<DappRequiredPermissionView[]>(
      `${this.base}/listings/${listingId}/versions/${versionId}/permissions`,
    );
  }

  versionPaymentMethods(listingId: string, versionId: string): Observable<PaymentMethodView[]> {
    return this.http.get<PaymentMethodView[]>(
      `${this.base}/listings/${listingId}/versions/${versionId}/payment-methods`,
    );
  }

  sign(listingId: string, versionId: string, signature: string, signerWallet: string): Observable<DappVersionView> {
    return this.http.post<DappVersionView>(
      `${this.base}/listings/${listingId}/versions/${versionId}/sign`,
      { signature, signerWallet },
    );
  }

  submit(listingId: string, versionId: string): Observable<DappVersionView> {
    return this.http.post<DappVersionView>(
      `${this.base}/listings/${listingId}/versions/${versionId}/submit`,
      {},
    );
  }
}
