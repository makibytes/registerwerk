import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { WebhookDelivery, WebhookEventType, WebhookSubscription } from '../models';

@Injectable({ providedIn: 'root' })
export class WebhookService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/me/webhooks`;

  list(): Observable<WebhookSubscription[]> {
    return this.http.get<WebhookSubscription[]>(this.base);
  }

  create(url: string, eventTypes: WebhookEventType[]): Observable<WebhookSubscription> {
    return this.http.post<WebhookSubscription>(this.base, { url, eventTypes });
  }

  setEnabled(id: string, enabled: boolean): Observable<void> {
    return this.http.put<void>(`${this.base}/${id}/enabled`, { enabled });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  deliveries(id: string): Observable<WebhookDelivery[]> {
    return this.http.get<WebhookDelivery[]>(`${this.base}/${id}/deliveries`);
  }
}
