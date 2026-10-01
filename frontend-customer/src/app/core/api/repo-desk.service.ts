import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  RepoCollateral, RepoCounterparty, RepoParticipation, RepoRfq, RepoSftrFields, RepoTrade,
} from '../models';

export type RepoLeg = 'CASH' | 'COLLATERAL';
export type RepoPhase = 'open' | 'close';
export type RepoSubstitutionLeg = 'REPLACEMENT_IN' | 'ORIGINAL_OUT';

export interface RepoMarginCallBody {
  amount: number; dueAt: string; valuationReference: string; valuationAmount: number; note: string | null;
}

@Injectable({ providedIn: 'root' })
export class RepoDeskService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/repo-desk`;
  private readonly tradeBase = `${this.base}/trades`;

  // --- participation and directory -------------------------------------------------------------
  participation(): Observable<RepoParticipation> { return this.http.get<RepoParticipation>(`${this.base}/participation`); }
  optIn(listed: boolean): Observable<RepoParticipation> { return this.http.put<RepoParticipation>(`${this.base}/participation`, { listed }); }
  optOut(): Observable<RepoParticipation> { return this.http.delete<RepoParticipation>(`${this.base}/participation`); }
  counterparties(): Observable<RepoCounterparty[]> { return this.http.get<RepoCounterparty[]>(`${this.base}/counterparties`); }
  collateral(): Observable<RepoCollateral[]> { return this.http.get<RepoCollateral[]>(`${this.base}/collateral`); }

  // --- RFQs and quotes -------------------------------------------------------------------------
  listRfqs(): Observable<RepoRfq[]> { return this.http.get<RepoRfq[]>(`${this.base}/rfqs`); }
  createRfq(body: unknown): Observable<RepoRfq> { return this.http.post<RepoRfq>(`${this.base}/rfqs`, body); }
  cancelRfq(id: string): Observable<RepoRfq> { return this.http.post<RepoRfq>(`${this.base}/rfqs/${id}/cancel`, {}); }
  quote(id: string, body: unknown): Observable<RepoRfq> { return this.http.put<RepoRfq>(`${this.base}/rfqs/${id}/quote`, body); }
  withdrawQuote(id: string): Observable<RepoRfq> { return this.http.delete<RepoRfq>(`${this.base}/rfqs/${id}/quote`); }
  /** The server-computed {@code termsHash} of the quote the user saw; a re-quote in between answers 409. */
  acceptQuote(rfqId: string, quoteId: string, termsHash: string): Observable<RepoRfq> {
    return this.http.post<RepoRfq>(`${this.base}/rfqs/${rfqId}/quotes/${quoteId}/accept`, { termsHash });
  }

  // --- trades ----------------------------------------------------------------------------------
  listTrades(): Observable<RepoTrade[]> { return this.http.get<RepoTrade[]>(this.tradeBase); }
  sftrFields(id: string): Observable<RepoSftrFields> { return this.http.get<RepoSftrFields>(`${this.tradeBase}/${id}/sftr-fields`); }

  /** Payer declares the leg as sent. */
  declareLeg(id: string, phase: RepoPhase, leg: RepoLeg, reference: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/${phase}-settlement/${leg}/declare`, { reference });
  }
  /** Recipient confirms receipt of the leg. */
  confirmLeg(id: string, phase: RepoPhase, leg: RepoLeg, reference: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/${phase}-settlement/${leg}`, { reference });
  }
  initiateClose(id: string): Observable<RepoTrade> { return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/close`, {}); }

  marginCall(id: string, body: RepoMarginCallBody): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/margin-call`, body);
  }
  /** Borrower: top-up was sent. Does not clear the call. */
  marginDelivered(id: string, reference: string, note: string | null): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/margin-call/delivered`, { reference, note });
  }
  /** Lender: top-up arrived. Clears the call. */
  marginConfirm(id: string, reference: string, note: string | null): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/margin-call/confirm`, { reference, note });
  }

  requestSubstitution(id: string, assetId: string, quantity: number, note: string | null): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/substitution`, { assetId, quantity, note });
  }
  decideSubstitution(id: string, requestId: string, approve: boolean, note: string | null): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/substitution/${requestId}/decision`, { approve, note });
  }
  withdrawSubstitution(id: string, note: string | null): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/substitution/withdraw`, { note });
  }
  confirmSubstitutionLeg(id: string, leg: RepoSubstitutionLeg, reference: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/substitution/${leg}`, { reference });
  }

  defaultNotice(id: string, note: string | null): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/default-notice`, { note });
  }
  declareDefault(id: string, note: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/default`, { note });
  }
  dispute(id: string, note: string): Observable<RepoTrade> { return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/dispute`, { note }); }
  addNote(id: string, note: string): Observable<RepoTrade> { return this.http.post<RepoTrade>(`${this.tradeBase}/${id}/notes`, { note }); }
}
