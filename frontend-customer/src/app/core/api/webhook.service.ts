import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
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

  /**
   * Issues a new signing secret (returned once); the old one keeps signing for the overlap window.
   * Needs step-up: under built-in sign-in pass the scoped token, under Entra omit it (the error
   * interceptor answers the claims challenge).
   */
  rotateSecret(id: string, stepUpToken?: string): Observable<WebhookSubscription> {
    const headers = stepUpToken ? new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` }) : undefined;
    return this.http.post<WebhookSubscription>(`${this.base}/${id}/rotate-secret`, {}, { headers });
  }

  /** Exchanges an authenticator code for a step-up token scoped to `action` (built-in sign-in only). */
  stepUp(totpCode: string, action: string): Observable<{ stepUpToken: string }> {
    return this.http.post<{ stepUpToken: string }>(`${environment.apiUrl}/auth/step-up`,
      { code: totpCode, method: 'TOTP', action });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  deliveries(id: string): Observable<WebhookDelivery[]> {
    return this.http.get<WebhookDelivery[]>(`${this.base}/${id}/deliveries`);
  }
}
