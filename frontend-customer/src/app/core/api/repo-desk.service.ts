import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { RepoCollateral, RepoCounterparty, RepoRfq, RepoTrade } from '../models';

@Injectable({ providedIn: 'root' })
export class RepoDeskService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/repo-desk`;
  listRfqs(): Observable<RepoRfq[]> { return this.http.get<RepoRfq[]>(`${this.base}/rfqs`); }
  createRfq(body: unknown): Observable<RepoRfq> { return this.http.post<RepoRfq>(`${this.base}/rfqs`, body); }
  cancelRfq(id: string): Observable<RepoRfq> { return this.http.post<RepoRfq>(`${this.base}/rfqs/${id}/cancel`, {}); }
  quote(id: string, body: unknown): Observable<RepoRfq> { return this.http.put<RepoRfq>(`${this.base}/rfqs/${id}/quote`, body); }
  withdrawQuote(id: string): Observable<RepoRfq> { return this.http.delete<RepoRfq>(`${this.base}/rfqs/${id}/quote`); }
  acceptQuote(rfqId: string, quoteId: string): Observable<RepoRfq> {
    return this.http.post<RepoRfq>(`${this.base}/rfqs/${rfqId}/quotes/${quoteId}/accept`, {});
  }
  counterparties(): Observable<RepoCounterparty[]> { return this.http.get<RepoCounterparty[]>(`${this.base}/counterparties`); }
  collateral(): Observable<RepoCollateral[]> { return this.http.get<RepoCollateral[]>(`${this.base}/collateral`); }
  listTrades(): Observable<RepoTrade[]> { return this.http.get<RepoTrade[]>(`${this.base}/trades`); }
  confirmOpen(id: string, leg: 'CASH' | 'COLLATERAL', reference: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.base}/trades/${id}/open-settlement/${leg}`, { reference });
  }
  marginCall(id: string, amount: number, dueAt: string, note: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.base}/trades/${id}/margin-call`, { amount, dueAt, note });
  }
  satisfyMargin(id: string, reference: string, note: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.base}/trades/${id}/margin-call/satisfy`, { reference, note });
  }
  requestSubstitution(id: string, assetId: string, quantity: number, note: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.base}/trades/${id}/substitution`, { assetId, quantity, note });
  }
  decideSubstitution(id: string, approve: boolean, note: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.base}/trades/${id}/substitution/decision`, { approve, note });
  }
  initiateClose(id: string): Observable<RepoTrade> { return this.http.post<RepoTrade>(`${this.base}/trades/${id}/close`, {}); }
  confirmClose(id: string, leg: 'CASH' | 'COLLATERAL', reference: string): Observable<RepoTrade> {
    return this.http.post<RepoTrade>(`${this.base}/trades/${id}/close-settlement/${leg}`, { reference });
  }
}

