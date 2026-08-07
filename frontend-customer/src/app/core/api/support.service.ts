import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SupportTicket, SupportTicketMessage } from '../models';

/**
 * Wraps `support.web.MeSupportTicketController` (`/api/v1/me/support-tickets`), which had no
 * frontend caller: previously DSAR erasure and KYC document review were the only
 * customer-initiated channels, both narrow compliance flows rather than general support.
 */
@Injectable({ providedIn: 'root' })
export class SupportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/me/support-tickets`;

  list(): Observable<SupportTicket[]> {
    return this.http.get<SupportTicket[]>(this.base);
  }

  create(subject: string, description: string, category: SupportTicket['category'], priority?: SupportTicket['priority']): Observable<SupportTicket> {
    return this.http.post<SupportTicket>(this.base, { subject, description, category, priority });
  }

  get(ticketId: string): Observable<SupportTicket> {
    return this.http.get<SupportTicket>(`${this.base}/${ticketId}`);
  }

  messages(ticketId: string): Observable<SupportTicketMessage[]> {
    return this.http.get<SupportTicketMessage[]>(`${this.base}/${ticketId}/messages`);
  }

  addMessage(ticketId: string, body: string): Observable<SupportTicketMessage> {
    return this.http.post<SupportTicketMessage>(`${this.base}/${ticketId}/messages`, { body });
  }
}
