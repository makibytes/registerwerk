import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
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

export type InstructingParty = 'HOLDER' | 'BENEFICIARY' | 'COURT' | 'INSOLVENCY_ADMINISTRATOR' | 'ISSUER_TERMS_CHANGE';

export interface HolderEntryRequest {
  investorId: string;
  walletAddress: string;
  nominalAmount: number;
  instructingParty: InstructingParty;
  instructionReference: string;
}

export interface HolderChangeRequest {
  id: string;
  assetId: string;
  requestType: 'ADD_HOLDER' | 'UPDATE_ATTRIBUTES';
  status: 'REQUESTED' | 'EXECUTED' | 'REJECTED';
  instructingParty: InstructingParty;
  instructionReference: string;
  requestedAt: string;
  decidedAt: string | null;
  decisionReason: string | null;
}

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

  /**
   * From APPROVED on, the backend refuses changed economic terms (ISIN, currency, issue size,
   * denomination, dates; min/max investment from ISSUED) with 400 — those change only through an
   * operator 4-eyes terms amendment. Name and public data stay editable.
   */
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

  /**
   * The issuer can no longer write register entries itself (T3-13): it files a change request that
   * the registry operator executes against the instruction it relays.
   */
  requestHolderEntry(assetId: string, body: HolderEntryRequest): Observable<HolderChangeRequest> {
    return this.http.post<HolderChangeRequest>(`${this.base}/${assetId}/holders/change-requests`, {
      requestType: 'ADD_HOLDER',
      payload: { investorId: body.investorId, walletAddress: body.walletAddress, nominalAmount: body.nominalAmount },
      instructingParty: body.instructingParty,
      instructionReference: body.instructionReference,
    });
  }

  listHolderChangeRequests(assetId: string): Observable<HolderChangeRequest[]> {
    return this.http.get<HolderChangeRequest[]>(`${this.base}/${assetId}/holders/change-requests`);
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

  /**
   * Mint needs step-up and a second approver (`ISSUER_MINT`, P4C-2); the recipient must be a registered,
   * KYC-approved, screened holder of the asset. Same token handling as {@link burn}.
   */
  mint(assetId: string, depId: string, body: { toAddress: string; amount: string; reason?: string },
       approval: { approvalToken: string; stepUpToken?: string }): Observable<{ txId: string; destinationHolder?: string | null }> {
    let headers = new HttpHeaders({ 'X-Dual-Control-Token': approval.approvalToken });
    if (approval.stepUpToken) {
      headers = headers.set('Authorization', `Bearer ${approval.stepUpToken}`);
    }
    return this.http.post<{ txId: string; destinationHolder?: string | null }>(
      `${this.base}/${assetId}/deployments/${depId}/issuer/mint`, body, { headers });
  }

  /**
   * Burn is a §26 Einziehung (T3-01): the backend requires an ASSET_TOKEN_ADMIN grant, step-up and a
   * second approver. `approvalToken` is a registry administrator's step-up token scoped to
   * `ISSUER_BURN_EWG26`; `stepUpToken` replaces the session bearer under built-in sign-in. Under
   * Entra it is omitted and the error interceptor answers the claims challenge.
   */
  burn(assetId: string, depId: string, body: { fromAddress: string; amount: string },
       approval: { approvalToken: string; stepUpToken?: string }): Observable<{ txId: string }> {
    let headers = new HttpHeaders({ 'X-Dual-Control-Token': approval.approvalToken });
    if (approval.stepUpToken) {
      headers = headers.set('Authorization', `Bearer ${approval.stepUpToken}`);
    }
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/burn`, body, { headers });
  }

  /**
   * Exchanges an authenticator code for the initiator's own step-up token (built-in sign-in only). The
   * `action` parameter is intentionally not sent: the backend treats an action as a second-approver
   * request and then demands a bound `target`, which the initiator's own token never has (8B-01).
   */
  stepUp(totpCode: string, _action?: string): Observable<{ stepUpToken: string }> {
    return this.http.post<{ stepUpToken: string }>(`${environment.apiUrl}/auth/step-up`,
      { code: totpCode, method: 'TOTP' });
  }

  /**
   * Issuer forced transfer (`ISSUER_FORCED_TRANSFER_EWG24`): step-up + second approver, like {@link burn};
   * the destination must be a registered, KYC-approved, screened holder (P4C-2); legal basis >= 10 chars.
   */
  forceTransfer(assetId: string, depId: string, body: { from: string; to: string; value: string; legalBasis: string },
                approval: { approvalToken: string; stepUpToken?: string }): Observable<{ txId: string; destinationHolder?: string | null }> {
    return this.http.post<{ txId: string; destinationHolder?: string | null }>(
      `${this.base}/${assetId}/deployments/${depId}/issuer/forced-transfer`, body, { headers: this.approvalHeaders(approval) });
  }

  /** Issuer forced allowance override (`ISSUER_FORCED_APPROVE_OVERRIDE`): step-up + second approver. */
  forceApprove(assetId: string, depId: string, body: { owner: string; spender: string; value: string; legalBasis: string },
               approval: { approvalToken: string; stepUpToken?: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/${assetId}/deployments/${depId}/issuer/forced-approve`, body, { headers: this.approvalHeaders(approval) });
  }

  private approvalHeaders(approval: { approvalToken: string; stepUpToken?: string }): HttpHeaders {
    let headers = new HttpHeaders({ 'X-Dual-Control-Token': approval.approvalToken });
    if (approval.stepUpToken) {
      headers = headers.set('Authorization', `Bearer ${approval.stepUpToken}`);
    }
    return headers;
  }

  /**
   * Confidential mint (CONF_ERC20/CONF_ERC3643 only) — the amount is encrypted server-side via
   * the backend's `zama-relayer` sidecar (there is no browser/wallet in an issuer-initiated
   * mint), unlike an investor's own confidential transfer, which {@link FheClientService}
   * encrypts entirely client-side.
   */
  mintConfidential(assetId: string, depId: string, body: { toAddress: string; amount: string; reason?: string },
                   approval: { approvalToken: string; stepUpToken?: string }): Observable<{ txId: string; destinationHolder?: string | null }> {
    return this.http.post<{ txId: string; destinationHolder?: string | null }>(
      `${this.base}/${assetId}/deployments/${depId}/issuer/mint-confidential`, body,
      { headers: this.approvalHeaders(approval) });
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
  currency: string | null;
  issueSize: number | null;
  denomination: number | null;
  issueDate: string | null;
  maturityDate: string | null;
}

/** Mirrors the backend's `ConfidentialContextResponse`. */
export interface ConfidentialContext {
  contractAddress: string;
  chain: string;
  network: string;
  chainId: number;
}
