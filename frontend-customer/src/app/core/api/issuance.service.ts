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
  Jurisdiction,
  OnchainLevel,
  TokenStandard,
} from '../models';
import { LiveHolder } from '../../shared/components/token-holders/models';

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

  createIssuance(body: IssuanceCreateRequest): Observable<Asset> {
    return this.http.post<Asset>(this.base, body);
  }

  updateIssuance(id: string, body: Partial<Asset>): Observable<Asset> {
    return this.http.put<Asset>(`${this.base}/${id}`, body);
  }

  // ── Lifecycle transitions ──────────────────────────────────────────────────

  submitIssuance(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/submit`, {});
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

  mint(assetId: string, depId: string, body: { toAddress: string; amount: string; reason?: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/mint`, body);
  }

  burn(assetId: string, depId: string, body: { fromAddress: string; amount: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/burn`, body);
  }

  forceTransfer(assetId: string, depId: string, body: { from: string; to: string; value: string; legalBasis: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/forced-transfer`, body);
  }

  forceApprove(assetId: string, depId: string, body: { owner: string; spender: string; value: string; legalBasis: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/forced-approve`, body);
  }

  /**
   * Confidential mint (CONF_ERC20/CONF_ERC3643 only) — the amount is encrypted server-side via
   * the backend's `zama-relayer` sidecar (there is no browser/wallet in an issuer-initiated
   * mint), unlike an investor's own confidential transfer, which {@link FheClientService}
   * encrypts entirely client-side.
   */
  mintConfidential(assetId: string, depId: string, body: { toAddress: string; amount: string; reason?: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/mint-confidential`, body);
  }

  /**
   * Contract address + chain id for a confidential deployment — everything
   * {@link FheClientService}'s `encrypt64`/`userDecrypt` need to talk to Zama's relayer directly.
   */
  getConfidentialContext(assetId: string, depId: string): Observable<ConfidentialContext> {
    return this.http.get<ConfidentialContext>(`${this.base}/${assetId}/deployments/${depId}/confidential-context`);
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

export interface IssuanceCreateRequest {
  name: string;
  isin: string | null;
  jurisdiction: Jurisdiction | null;
  onchainLevel: OnchainLevel;
  chain: Chain | null;
  network: Network | null;
  tokenStandard: TokenStandard;
}

/** Mirrors the backend's `ConfidentialContextResponse`. */
export interface ConfidentialContext {
  contractAddress: string;
  chain: string;
  network: string;
  chainId: number;
}
