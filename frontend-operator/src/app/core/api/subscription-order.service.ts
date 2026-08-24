import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
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
}
