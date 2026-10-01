import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PaymentRailRequest, PaymentRailView } from '../models';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

@Injectable({ providedIn: 'root' })
export class PaymentRailService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/payment-rails`;

  list(): Observable<PaymentRailView[]> {
    return this.http.get<PaymentRailView[]>(this.base);
  }

  /** Needs step-up AND a second approver (`X-Dual-Control-Token`); the rail starts disabled. */
  create(body: PaymentRailRequest, tokens: DualControlTokens): Observable<PaymentRailView> {
    return this.http.post<PaymentRailView>(this.base, body, { headers: dualControlHeaders(tokens) });
  }

  update(railId: string, body: PaymentRailRequest, tokens: DualControlTokens): Observable<PaymentRailView> {
    return this.http.put<PaymentRailView>(`${this.base}/${railId}`, body, { headers: dualControlHeaders(tokens) });
  }

  /** Enabling is dual-control; an EMT stablecoin rail additionally needs an effective MiCAR attestation. */
  enable(railId: string, tokens: DualControlTokens): Observable<PaymentRailView> {
    return this.http.post<PaymentRailView>(`${this.base}/${railId}/enable`, {}, { headers: dualControlHeaders(tokens) });
  }

  /** Disabling is the safe direction: single step-up. */
  disable(railId: string, stepUpToken: string): Observable<PaymentRailView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<PaymentRailView>(`${this.base}/${railId}/disable`, {}, { headers });
  }

  /**
   * Records the operator's own attestation that the MiCAR fields were checked against an external source
   * (not a register cross-check). Dual-control; the attester must differ from the rail's creator and last editor.
   */
  verifyMicar(railId: string, tokens: DualControlTokens): Observable<PaymentRailView> {
    return this.http.post<PaymentRailView>(`${this.base}/${railId}/verify-micar`, {}, { headers: dualControlHeaders(tokens) });
  }

  unverifyMicar(railId: string, stepUpToken: string): Observable<PaymentRailView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<PaymentRailView>(`${this.base}/${railId}/unverify-micar`, {}, { headers });
  }
}
