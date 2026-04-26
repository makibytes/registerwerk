import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  Asset,
  AssetDeployment,
  AssetDocument,
  AssetHolder,
  Chain,
  Network,
  PageParams,
  PageResponse,
} from '../models';

@Injectable({ providedIn: 'root' })
export class IssuanceService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/assets`;

  // ── Asset CRUD ─────────────────────────────────────────────────────────────

  getIssuances(params?: PageParams): Observable<PageResponse<Asset>> {
    const httpParams = this.buildParams(params);
    return this.http.get<PageResponse<Asset>>(this.base, { params: httpParams });
  }

  getIssuance(id: string): Observable<Asset> {
    return this.http.get<Asset>(`${this.base}/${id}`);
  }

  createIssuance(body: Partial<Asset>): Observable<Asset> {
    return this.http.post<Asset>(this.base, body);
  }

  updateIssuance(id: string, body: Partial<Asset>): Observable<Asset> {
    return this.http.put<Asset>(`${this.base}/${id}`, body);
  }

  // ── Lifecycle transitions ──────────────────────────────────────────────────

  submitIssuance(id: string): Observable<Asset> {
    return this.http.post<Asset>(`${this.base}/${id}/submit`, {});
  }

  // ── Deployments ────────────────────────────────────────────────────────────

  deployIssuance(id: string, chain: Chain, network: Network): Observable<AssetDeployment> {
    return this.http.post<AssetDeployment>(`${this.base}/${id}/deploy`, { chain, network });
  }

  getDeployments(assetId: string): Observable<AssetDeployment[]> {
    return this.http.get<AssetDeployment[]>(`${this.base}/${assetId}/deployments`);
  }

  // ── Holders ────────────────────────────────────────────────────────────────

  getHolders(assetId: string, params?: PageParams): Observable<PageResponse<AssetHolder>> {
    const httpParams = this.buildParams(params);
    return this.http.get<PageResponse<AssetHolder>>(
      `${this.base}/${assetId}/holders`,
      { params: httpParams }
    );
  }

  addHolder(assetId: string, body: { walletAddress: string; nominalAmount: number }): Observable<AssetHolder> {
    return this.http.post<AssetHolder>(`${this.base}/${assetId}/holders`, body);
  }

  // ── Documents ──────────────────────────────────────────────────────────────

  listDocuments(assetId: string): Observable<AssetDocument[]> {
    return this.http.get<AssetDocument[]>(`${this.base}/${assetId}/documents`);
  }

  uploadDocument(assetId: string, file: File, documentType = 'TERM_SHEET'): Observable<AssetDocument> {
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('documentType', documentType);
    return this.http.post<AssetDocument>(`${this.base}/${assetId}/documents`, form);
  }

  downloadDocument(assetId: string, docId: string): Observable<Blob> {
    return this.http.get(`${this.base}/${assetId}/documents/${docId}/content`, { responseType: 'blob' });
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private buildParams(params?: PageParams): HttpParams {
    let httpParams = new HttpParams();
    if (!params) return httpParams;
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        httpParams = httpParams.set(key, String(value));
      }
    });
    return httpParams;
  }
}
