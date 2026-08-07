import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse, SupportTicket, SupportTicketMessage } from '../models';

/**
 * Wraps `support.web.SupportTicketAdminController` (`/api/v1/admin/support-tickets`), which
 * had no operator frontend caller: it is the only real assignment/ownership model in the
 * system, and was unreachable by the staff who would use it.
 */
@Injectable({ providedIn: 'root' })
export class SupportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/support-tickets`;

  list(status?: SupportTicket['status'], page = 0, size = 50): Observable<PageResponse<SupportTicket>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) params = params.set('status', status);
    return this.http.get<PageResponse<SupportTicket>>(this.base, { params });
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

  assign(ticketId: string, assigneeId: string): Observable<SupportTicket> {
    const params = new HttpParams().set('assigneeId', assigneeId);
    return this.http.post<SupportTicket>(`${this.base}/${ticketId}/assign`, {}, { params });
  }

  resolve(ticketId: string, resolutionNotes: string): Observable<SupportTicket> {
    return this.http.post<SupportTicket>(`${this.base}/${ticketId}/resolve`, { resolutionNotes });
  }

  close(ticketId: string): Observable<SupportTicket> {
    return this.http.post<SupportTicket>(`${this.base}/${ticketId}/close`, {});
  }

  reopen(ticketId: string): Observable<SupportTicket> {
    return this.http.post<SupportTicket>(`${this.base}/${ticketId}/reopen`, {});
  }
}
