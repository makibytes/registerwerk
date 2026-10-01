import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

export type RepoDisputeResolution = 'RESUME' | 'CLOSE' | 'CANCEL';

export interface RepoDisputeEntry {
  type: string;
  actor: string | null;
  reference: string | null;
  note: string | null;
  at: string;
}

export interface RepoDispute {
  id: string;
  status: string;
  borrower: string;
  lender: string;
  collateral: string;
  isin: string | null;
  collateralQuantity: number;
  cashAmount: number;
  currency: string;
  repurchaseAmount: number;
  disputeReason: string | null;
  /** The state the trade was in when it was frozen; decides which resolutions are possible. */
  preDisputeStatus: string | null;
  disputedAt: string | null;
  uti: string | null;
  events: RepoDisputeEntry[];
}

/** Operator queue for disputed repo trades (`/api/v1/admin/repo`). */
@Injectable({ providedIn: 'root' })
export class RepoAdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/repo`;

  listDisputes(): Observable<RepoDispute[]> {
    return this.http.get<RepoDispute[]>(`${this.base}/disputes`);
  }

  /**
   * Records how a dispute ends (scope `REPO_DISPUTE_RESOLVE`: step-up + second approver + legal basis).
   * The operator records an outcome; it does not decide the merits of the dispute.
   */
  resolve(
    tradeId: string,
    body: { resolution: RepoDisputeResolution; legalBasis: string; note: string | null },
    tokens: DualControlTokens,
  ): Observable<RepoDispute> {
    return this.http.post<RepoDispute>(`${this.base}/disputes/${tradeId}/resolve`, body, { headers: dualControlHeaders(tokens) });
  }
}

/** Resolutions the backend accepts for a dispute raised in the given state. */
export function allowedDisputeResolutions(preDisputeStatus: string | null): RepoDisputeResolution[] {
  const options: RepoDisputeResolution[] = ['RESUME'];
  if (preDisputeStatus === 'PENDING_CLOSE') options.push('CLOSE');
  if (preDisputeStatus === 'PENDING_OPEN_SETTLEMENT') options.push('CANCEL');
  return options;
}
