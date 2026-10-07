import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';
import { parseJsonPreservingBigInts } from './json-bigint.util';

export interface LendingMarketAdminView {
  id: string;
  chainConfigId: string;
  marketAddress: string;
  vaultAddress: string | null;
  collateralAssetId: string | null;
  collateralAssetName: string | null;
  collateralIsin: string | null;
  collateralTokenAddress: string;
  loanTokenAddress: string;
  loanRailCode: string | null;
  loanTokenDecimals: number | null;
  status: string;
  riskParametersLegacy: boolean;
  /** COLLATERAL_SHORTFALL | BINDING_UNVERIFIED | BORROW_PAUSED_ONCHAIN | CHAIN_READ_FAILED | null */
  pauseReason: string | null;
  bindingVerified: boolean;
  bindingFailure: string | null;
  collateralShortfall: boolean;
  createdAt: string;
}

export interface LendingReconciliationTask {
  id: string;
  marketId: string;
  status: string;
  source: string;
  /** Token base units, kept as a string (exceeds Number.MAX_SAFE_INTEGER). */
  shortfall: string | null;
  tokenAdminMethod: string | null;
  detail: string | null;
  detectedAt: string;
  reconcileTxHash: string | null;
  borrowerWallet: string | null;
  attributedAmount: string | null;
  forcedTransferRef: string | null;
  suggestedCalls: string;
}

export interface RegisterMarketRequest {
  chainConfigId: string;
  marketAddress: string;
  vaultAddress: string | null;
  collateralAssetId: string;
  loanRailCode: string | null;
}

export interface ReverifyResult {
  marketId: string;
  marketAddress: string;
  verified: boolean;
  failure: string | null;
}

export interface ReconcileCollateralRequest {
  borrowerWallet: string;
  attributableCollateral: string;
  forcedTransferRef: string;
  legalBasis: string;
}

/** One market's outcome of the legacy borrow-pause sweep (`SUBMITTED`, `ALREADY_PAUSED`, `NOT_CHECKED`, ...). */
export interface LegacyPauseEnforcement {
  marketId: string;
  marketAddress: string;
  outcome: string;
  txHash: string | null;
}

/** One-line summary for the snackbar; markets that could not be checked are called out, never counted as paused. */
export function summarizeLegacyPause(results: LegacyPauseEnforcement[]): string {
  if (results.length === 0) return 'No unverified or legacy markets need an on-chain pause.';
  const count = (o: string) => results.filter((r) => r.outcome === o).length;
  const submitted = count('SUBMITTED');
  const unchecked = count('NOT_CHECKED');
  const other = results.length - submitted - unchecked;
  const parts = [`${submitted} pause transaction${submitted === 1 ? '' : 's'} submitted`];
  if (other > 0) parts.push(`${other} already paused or not applicable`);
  if (unchecked > 0) parts.push(`${unchecked} could not be read on-chain and were NOT paused`);
  return parts.join('; ') + '.';
}

/**
 * Operator side of the lending read-model. The mutating calls are `@RequiresIdempotencyKey` (the
 * idempotency interceptor adds the header) and step-up protected; registration, reconcile and borrow-pause
 * additionally need a second approver (`X-Dual-Control-Token`).
 */
@Injectable({ providedIn: 'root' })
export class LendingAdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/lending`;

  listMarkets(): Observable<LendingMarketAdminView[]> {
    return this.getPreservingBigInts<LendingMarketAdminView[]>(`${this.base}/markets`);
  }

  registerMarket(body: RegisterMarketRequest, tokens: DualControlTokens): Observable<LendingMarketAdminView> {
    return this.http.post<LendingMarketAdminView>(`${this.base}/markets`, body, { headers: dualControlHeaders(tokens) });
  }

  /** Flags (never deletes) markets whose factory / collateral / loan-token binding no longer verifies. */
  reverify(stepUpToken: string): Observable<ReverifyResult[]> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<ReverifyResult[]>(`${this.base}/markets/reverify`, {}, { headers });
  }

  listReconciliationTasks(): Observable<LendingReconciliationTask[]> {
    return this.getPreservingBigInts<LendingReconciliationTask[]>(`${this.base}/reconciliation-tasks`);
  }

  reconcileCollateral(
    marketId: string, body: ReconcileCollateralRequest, tokens: DualControlTokens,
  ): Observable<LendingReconciliationTask> {
    return this.http.post<LendingReconciliationTask>(
      `${this.base}/markets/${marketId}/reconcile-collateral`, body, { headers: dualControlHeaders(tokens) });
  }

  setBorrowPaused(
    marketId: string, paused: boolean, reason: string, tokens: DualControlTokens,
  ): Observable<{ txHash: string }> {
    return this.http.post<{ txHash: string }>(
      `${this.base}/markets/${marketId}/borrow-paused`, { paused, reason }, { headers: dualControlHeaders(tokens) });
  }

  /**
   * H11: submits `setBorrowPaused(true)` for every unverified / legacy market that is not paused on-chain.
   * Only ever pauses; step-up + second approver (`Lending legacy market borrow pause`).
   */
  legacyBorrowPause(tokens: DualControlTokens): Observable<LegacyPauseEnforcement[]> {
    return this.http.post<LegacyPauseEnforcement[]>(
      `${this.base}/markets/legacy-borrow-pause`, {}, { headers: dualControlHeaders(tokens) });
  }

  private getPreservingBigInts<T>(url: string): Observable<T> {
    return this.http.get(url, { responseType: 'text' }).pipe(map((text) => parseJsonPreservingBigInts(text) as T));
  }
}
