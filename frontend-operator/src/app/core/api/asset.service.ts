import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  Asset,
  AssetDeployment,
  AssetDocument,
  AssetHolder,
  KycComplianceResponse,
  PageResponse,
  AssetFilterParams,
} from '../models';

@Injectable({ providedIn: 'root' })
export class AssetService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/assets`;

  getAssets(params: AssetFilterParams = {}): Observable<PageResponse<Asset>> {
    let httpParams = new HttpParams();
    if (params.status) httpParams = httpParams.set('status', params.status);
    if (params.tokenStandard) httpParams = httpParams.set('tokenStandard', params.tokenStandard);
    if (params.issuerId) httpParams = httpParams.set('issuerId', params.issuerId);
    if (params.search) httpParams = httpParams.set('search', params.search);
    if (params.page != null) httpParams = httpParams.set('page', params.page.toString());
    if (params.size != null) httpParams = httpParams.set('size', params.size.toString());
    if (params.sort) httpParams = httpParams.set('sort', params.sort);

    return this.http.get<PageResponse<Asset>>(this.base, { params: httpParams });
  }

  getAsset(id: string): Observable<Asset> {
    return this.http.get<Asset>(`${this.base}/${id}`);
  }

  createAsset(body: Partial<Asset>): Observable<Asset> {
    return this.http.post<Asset>(this.base, body);
  }

  updateAsset(id: string, body: Partial<Asset>): Observable<Asset> {
    return this.http.put<Asset>(`${this.base}/${id}`, body);
  }

  approveAsset(id: string): Observable<Asset> {
    return this.http.post<Asset>(`${this.base}/${id}/approve`, {});
  }

  issueAsset(id: string): Observable<Asset> {
    return this.http.post<Asset>(`${this.base}/${id}/issue`, {});
  }

  suspendAsset(id: string): Observable<Asset> {
    return this.http.post<Asset>(`${this.base}/${id}/suspend`, {});
  }

  /** Correction path for a wrongful suspend — moves a SUSPENDED asset back to ISSUED. */
  reactivateAsset(id: string): Observable<Asset> {
    return this.http.post<Asset>(`${this.base}/${id}/reactivate`, {});
  }

  redeemAsset(id: string): Observable<Asset> {
    return this.http.post<Asset>(`${this.base}/${id}/redeem`, {});
  }

  getDeployments(assetId: string): Observable<AssetDeployment[]> {
    return this.http.get<AssetDeployment[]>(`${this.base}/${assetId}/deployments`);
  }

  getHolders(assetId: string): Observable<AssetHolder[]> {
    return this.http.get<AssetHolder[]>(`${this.base}/${assetId}/holders`);
  }

  mint(assetId: string, deploymentId: string, body: { toAddress: string; amount: number }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${deploymentId}/issuer/mint`, body);
  }

  burn(assetId: string, deploymentId: string, body: { fromAddress: string; amount: number }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${deploymentId}/issuer/burn`, body);
  }

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

  /** Full holder register as CSV — the registered DB entries, not the live on-chain balances. */
  exportHolders(assetId: string): Observable<Blob> {
    return this.http.get(`${this.base}/${assetId}/holders/export`, { responseType: 'blob' });
  }

  deleteDocument(assetId: string, docId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${assetId}/documents/${docId}`);
  }

  syncFromChain(assetId: string, deploymentId: string): Observable<AssetDocument> {
    return this.http.post<AssetDocument>(`${this.base}/${assetId}/documents/sync-from-chain`,
      { deploymentId });
  }

  getKycCompliance(assetId: string): Observable<KycComplianceResponse> {
    return this.http.get<KycComplianceResponse>(`${this.base}/${assetId}/kyc-compliance`);
  }

  deployAsset(assetId: string, body: { chain: string; network: string; tokenStandard: string }): Observable<AssetDeployment> {
    return this.http.post<AssetDeployment>(`${this.base}/${assetId}/deploy`, body);
  }
}
