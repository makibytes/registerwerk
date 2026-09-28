import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

/** Mirrors the backend's `ConfidentialContextResponse` — everything `FheClientService` needs to
 *  talk to Zama's relayer directly for a given confidential deployment. */
export interface ConfidentialContext {
  contractAddress: string;
  chain: string;
  network: string;
  chainId: number;
}

export interface HolderReconciliation {
  holderId: string;
  walletAddress: string;
  registerAmount: string;
  onchainAmount: string | null;
  matches: boolean;
  error: string | null;
}

export interface ReconciliationReport {
  assetId: string;
  contractAddress: string;
  holders: HolderReconciliation[];
  allMatch: boolean;
}

/**
 * Confidential (Zama fhEVM) token admin operations — viewer management, confidential mint, and
 * register-vs-on-chain reconciliation. See `ConfidentialViewerPanelComponent` for the operator/
 * auditor reveal-and-reconcile UI this backs.
 */
@Injectable({ providedIn: 'root' })
export class ConfidentialService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/assets`;

  getConfidentialContext(assetId: string, depId: string): Observable<ConfidentialContext> {
    return this.http.get<ConfidentialContext>(`${this.base}/${assetId}/deployments/${depId}/confidential-context`);
  }

  /** Headless reconciliation using the backend's OWN operator-decrypt key — no wallet needed. */
  getReconciliation(assetId: string): Observable<ReconciliationReport> {
    return this.http.get<ReconciliationReport>(`${this.base}/${assetId}/confidential-reconciliation`);
  }

  mintConfidential(assetId: string, depId: string, body: { toAddress: string; amount: string; reason?: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/${assetId}/deployments/${depId}/issuer/mint-confidential`, body);
  }

  addViewer(assetId: string, depId: string, viewerAddress: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/${assetId}/deployments/${depId}/admin/confidential-add-viewer`, { viewerAddress });
  }

  /** Step-up + 4-eyes (`CONFIDENTIAL_VIEWER_REVOKE`, T3-21). */
  removeViewer(assetId: string, depId: string, viewerAddress: string, tokens: DualControlTokens): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/${assetId}/deployments/${depId}/admin/confidential-remove-viewer`, { viewerAddress },
      { headers: dualControlHeaders(tokens) });
  }

  // ── ERC-3643-family admin actions (ConfidentialERC3643 — CONF_ERC3643 only) ──────────────

  pause(assetId: string, depId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/${assetId}/deployments/${depId}/admin/confidential-pause`, {});
  }

  unpause(assetId: string, depId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/${assetId}/deployments/${depId}/admin/confidential-unpause`, {});
  }

  /** Unfreeze (`frozen=false`) needs step-up + 4-eyes tokens (`UNFREEZE`, T3-16); freeze does not. */
  setAddressFrozen(assetId: string, depId: string, address: string, frozen: boolean, tokens?: DualControlTokens): Observable<{ txId: string }> {
    const action = frozen ? 'confidential-freeze' : 'confidential-unfreeze';
    return this.http.post<{ txId: string }>(
      `${this.base}/${assetId}/deployments/${depId}/admin/${action}`, { address },
      tokens ? { headers: dualControlHeaders(tokens) } : {});
  }

  forceBurn(assetId: string, depId: string, body: { from: string; value: string; legalBasis: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/${assetId}/deployments/${depId}/admin/force-burn-confidential`, body);
  }

  forcedTransfer(assetId: string, depId: string, body: { from: string; to: string; value: string; legalBasis: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(
      `${this.base}/${assetId}/deployments/${depId}/admin/confidential-forced-transfer`, body);
  }
}
