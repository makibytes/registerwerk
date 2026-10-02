import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

export interface TradeNote {
  id: string;
  actorRole: string;
  actorEntityId: string | null;
  text: string;
  createdAt: string;
}

export interface OperatorTradeExecution {
  id: string;
  side: string;
  listingId: string;
  assetId: string;
  assetNumber: string;
  assetName: string;
  isin: string | null;
  executedQuantity: number;
  unitPrice: number;
  totalPrice: number;
  paymentOption: string;
  settlementStatus: string;
  walletAddress: string;
  createdAt: string;
  paymentReference: string | null;
  paymentDeclaredAt: string | null;
  instantSettlement: boolean;
  disputeReason: string | null;
  unresolvedAt: string | null;
  unresolvedReason: string | null;
  /** Null on legacy trades ("currency not recorded"). */
  currency: string | null;
  paymentRailCode: string | null;
  relatedParty: boolean;
  relatedPartyReasons: string | null;
}

export interface UnresolvedTrade {
  trade: OperatorTradeExecution;
  buyerEntityId: string;
  sellerEntityId: string;
  ageHours: number;
  aged: boolean;
  notes: TradeNote[];
}

export interface TimeoutBacklog {
  overduePending: number;
  overdueAwaiting: number;
  unresolvedTotal: number;
  unresolvedAged: number;
  failedAfterDeclaredPayment: string[];
}

export type UnresolvedResolution = 'force-settle' | 'record-return-of-funds' | 'release';

/** Operator side of the secondary-market desk (`/api/v1/admin/trading`). */
@Injectable({ providedIn: 'root' })
export class TradingAdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/trading`;

  listUnresolved(): Observable<UnresolvedTrade[]> {
    return this.http.get<UnresolvedTrade[]>(`${this.base}/unresolved`);
  }

  /** Read-only: historic FAILED trades after a declared payment. Review only; not resolvable here. */
  listHistoricFailed(): Observable<UnresolvedTrade[]> {
    return this.http.get<UnresolvedTrade[]>(`${this.base}/historic-failed`);
  }

  backlog(): Observable<TimeoutBacklog> {
    return this.http.get<TimeoutBacklog>(`${this.base}/timeout-backlog`);
  }

  /**
   * force-settle, record-return-of-funds and release are step-up + second-approver actions and need a
   * legal basis. Scopes: TRADE_UNRESOLVED_FORCE_SETTLE / _RETURN_OF_FUNDS / _RELEASE.
   */
  resolve(
    executionId: string,
    resolution: UnresolvedResolution,
    body: { legalBasis: string; note?: string | null },
    tokens: DualControlTokens,
  ): Observable<OperatorTradeExecution> {
    return this.http.post<OperatorTradeExecution>(
      `${this.base}/unresolved/${executionId}/${resolution}`, body, { headers: dualControlHeaders(tokens) });
  }

  addNote(executionId: string, text: string): Observable<TradeNote> {
    return this.http.post<TradeNote>(`${this.base}/history/${executionId}/notes`, { text });
  }

  /** Order/execution record export for a market-abuse tool (interim scope, not a validated RTS 22 report). */
  orderHistoryCsv(from?: string, to?: string): Observable<Blob> {
    let params = new HttpParams();
    if (from) params = params.set('from', new Date(`${from}T00:00:00Z`).toISOString());
    if (to) params = params.set('to', new Date(`${to}T23:59:59Z`).toISOString());
    return this.http.get(`${this.base}/order-history`, { params, responseType: 'blob' });
  }
}
