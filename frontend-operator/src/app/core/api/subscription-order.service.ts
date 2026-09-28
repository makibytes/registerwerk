import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse, SubscriptionOrder } from '../models';

@Injectable({ providedIn: 'root' })
export class SubscriptionOrderService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}`;

  listForAsset(assetId: string, page = 0, size = 20): Observable<PageResponse<SubscriptionOrder>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<SubscriptionOrder>>(`${this.base}/assets/${assetId}/orders`, { params });
  }

  allocate(orderId: string, allocatedAmount: number): Observable<SubscriptionOrder> {
    return this.http.post<SubscriptionOrder>(`${this.base}/orders/${orderId}/allocate`, { allocatedAmount });
  }

  reject(orderId: string, reason: string): Observable<SubscriptionOrder> {
    return this.http.post<SubscriptionOrder>(`${this.base}/orders/${orderId}/reject`, { reason });
  }

  /** Cash arrived for an accepted allocation. Step-up (`SUBSCRIPTION_PAYMENT_CONFIRM`). */
  confirmPayment(orderId: string, body: { paidAmount: number; paymentReference: string; valueDate?: string },
                 stepUpToken: string): Observable<SubscriptionOrder> {
    return this.http.post<SubscriptionOrder>(`${this.base}/orders/${orderId}/confirm-payment`, body,
      { headers: stepUp(stepUpToken) });
  }

  /** Enters the position (compliance gates re-run server-side; mint on a deployed asset). Step-up `SUBSCRIPTION_SETTLE`. */
  settle(orderId: string, stepUpToken: string): Observable<SubscriptionOrder> {
    return this.http.post<SubscriptionOrder>(`${this.base}/orders/${orderId}/settle`, {},
      { headers: stepUp(stepUpToken) });
  }

  /** Gives an allocation back (a paid order is marked for refund). Step-up `SUBSCRIPTION_RELEASE`. */
  release(orderId: string, reason: string, stepUpToken: string): Observable<SubscriptionOrder> {
    return this.http.post<SubscriptionOrder>(`${this.base}/orders/${orderId}/release`, { reason },
      { headers: stepUp(stepUpToken) });
  }
}

function stepUp(token: string): HttpHeaders {
  return new HttpHeaders({ Authorization: `Bearer ${token}` });
}
