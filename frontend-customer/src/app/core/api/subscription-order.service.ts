import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SubscriptionOrder } from '../models';

@Injectable({ providedIn: 'root' })
export class SubscriptionOrderService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}`;

  submit(assetId: string, walletAddress: string, requestedAmount: number): Observable<SubscriptionOrder> {
    return this.http.post<SubscriptionOrder>(`${this.base}/assets/${assetId}/orders`, { walletAddress, requestedAmount });
  }

  myOrders(): Observable<SubscriptionOrder[]> {
    return this.http.get<SubscriptionOrder[]>(`${this.base}/me/orders`);
  }

  cancel(orderId: string): Observable<SubscriptionOrder> {
    return this.http.post<SubscriptionOrder>(`${this.base}/orders/${orderId}/cancel`, {});
  }

  confirm(orderId: string): Observable<SubscriptionOrder> {
    return this.http.post<SubscriptionOrder>(`${this.base}/orders/${orderId}/confirm`, {});
  }
}
