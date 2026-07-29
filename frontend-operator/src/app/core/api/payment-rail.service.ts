import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PaymentRailRequest, PaymentRailView } from '../models';

@Injectable({ providedIn: 'root' })
export class PaymentRailService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/payment-rails`;

  list(): Observable<PaymentRailView[]> {
    return this.http.get<PaymentRailView[]>(this.base);
  }

  create(body: PaymentRailRequest, stepUpToken: string): Observable<PaymentRailView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<PaymentRailView>(this.base, body, { headers });
  }

  update(
    railId: string,
    body: PaymentRailRequest,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<PaymentRailView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.put<PaymentRailView>(`${this.base}/${railId}`, body, { headers });
  }

  enable(railId: string, stepUpToken: string): Observable<PaymentRailView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<PaymentRailView>(`${this.base}/${railId}/enable`, {}, { headers });
  }

  disable(railId: string, stepUpToken: string): Observable<PaymentRailView> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<PaymentRailView>(`${this.base}/${railId}/disable`, {}, { headers });
  }
}
