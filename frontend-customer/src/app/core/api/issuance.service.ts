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
import { LiveHolder } from '@registerwerk/ui';

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

  getLiveHolders(assetId: string, depId: string): Observable<LiveHolder[]> {
    return this.http.get<LiveHolder[]>(`${this.base}/${assetId}/holders/${depId}/live`);
  }

  refreshHolders(assetId: string): Observable<{ status: string; message: string }> {
    return this.http.post<{ status: string; message: string }>(
      `${this.base}/${assetId}/holders/refresh`,
      {}
    );
  }

  // ── Issuer token operations ────────────────────────────────────────────────

  mint(assetId: string, depId: string, body: { toAddress: string; amount: string; reason?: string }): Observable<void> {
    return this.http.post<void>(`${this.base}/${assetId}/deployments/${depId}/issuer/mint`, body);
  }

  burn(assetId: string, depId: string, body: { fromAddress: string; amount: string }): Observable<void> {
    return this.http.post<void>(`${this.base}/${assetId}/deployments/${depId}/issuer/burn`, body);
  }

  forceTransfer(assetId: string, depId: string, body: { from: string; to: string; value: string; legalBasis: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/forced-transfer`, body);
  }

  forceApprove(assetId: string, depId: string, body: { owner: string; spender: string; value: string; legalBasis: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/forced-approve`, body);
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
