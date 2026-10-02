import { Injectable, inject } from '@angular/core';
import { fetchAllPages } from '../utils/paging.util';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';
import {
  Asset,
  AssetDeployment,
  AssetDocument,
  AssetHolder,
  HolderRegisterRow,
  KycComplianceResponse,
  PageResponse,
  AssetFilterParams,
  TokenTransferResponse,
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

  /** REGISTRY_ADMIN + step-up + 4-eyes (T3-01); refused (409) while holders are unpaid or pools hold units. */
  redeemAsset(id: string, body: { legalBasis: string; reference: string }, tokens: DualControlTokens): Observable<Asset> {
    return this.http.post<Asset>(`${this.base}/${id}/redeem`, body, { headers: dualControlHeaders(tokens) });
  }

  getDeployments(assetId: string): Observable<AssetDeployment[]> {
    return this.http.get<AssetDeployment[]>(`${this.base}/${assetId}/deployments`);
  }

  /**
   * The endpoint returns a `PageResponse` of register rows (`walletAddress`, `nominalAmount`, ...), not a
   * plain array; map it to the display model and compute each holder's share of the listed total.
   */
  getHolders(assetId: string): Observable<AssetHolder[]> {
    return this.getHoldersPaged(assetId).pipe(map(r => r.holders));
  }

  /**
   * All register pages (the backend silently clamps `size` to 200). `truncated` = the page cap was hit: the
   * rows are partial and their percentages (computed over the loaded rows only) are NOT shares of the register.
   */
  getHoldersPaged(assetId: string): Observable<{ holders: AssetHolder[]; truncated: boolean; total: number }> {
    return fetchAllPages<HolderRegisterRow>((page, size) =>
      this.http.get<PageResponse<HolderRegisterRow>>(`${this.base}/${assetId}/holders`, {
        params: new HttpParams().set('page', page).set('size', size),
      }),
    ).pipe(map(r => ({ holders: AssetService.toHolders(r.items), truncated: r.truncated, total: r.totalElements })));
  }

  static toHolders(rows: HolderRegisterRow[]): AssetHolder[] {
    const balances = rows.map(r => Number(r.nominalAmount ?? 0) || 0);
    const total = balances.reduce((a, b) => a + b, 0);
    return rows.map((r, i) => ({
      id: r.id,
      investorId: r.investorId,
      address: r.walletAddress ?? '',
      walletAddress: r.walletAddress ?? '',
      balance: balances[i],
      percentage: total > 0 ? (balances[i] / total) * 100 : 0,
      whitelisted: r.whitelisted,
    }));
  }

  /**
   * Step-up + second approver (`ISSUER_MINT`, P4C-2). The destination must be a registered, KYC-approved,
   * screened holder of the asset; the response echoes the resolved holder for confirmation.
   */
  mint(assetId: string, deploymentId: string, body: { toAddress: string; amount: string }, tokens: DualControlTokens):
      Observable<{ txId: string; destinationHolder?: string | null }> {
    return this.http.post<{ txId: string; destinationHolder?: string | null }>(
      `${this.base}/${assetId}/deployments/${deploymentId}/issuer/mint`, body, { headers: dualControlHeaders(tokens) });
  }

  /** Burn from any address is a §26 Einziehung: step-up + 4-eyes (`ISSUER_BURN_EWG26`, T3-01). */
  burn(assetId: string, deploymentId: string, body: { fromAddress: string; amount: string }, tokens: DualControlTokens): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${deploymentId}/issuer/burn`, body,
      { headers: dualControlHeaders(tokens) });
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

  /**
   * Replaces the term sheet of an ISSUED/SUSPENDED/REDEEMED asset: step-up + second approver
   * (`TERM_SHEET_AMENDMENT`). A plain upload of a TERM_SHEET there is refused with 403. The previous
   * version is kept and marked superseded.
   */
  amendTermSheet(assetId: string, file: File, tokens: DualControlTokens): Observable<AssetDocument> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<AssetDocument>(`${this.base}/${assetId}/documents/term-sheet-amendment`, form,
      { headers: dualControlHeaders(tokens) });
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

  /**
   * Indexed on-chain token transfers for this asset, across all its deployments — the
   * `token_transfer` table via `TokenHistoryController`, not the registry's own
   * `blockchain_transaction` log (see {@link TransactionService}).
   */
  getTransferHistory(assetId: string, page = 0, size = 20): Observable<PageResponse<TokenTransferResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<TokenTransferResponse>>(`${this.base}/${assetId}/history`, { params });
  }
}
