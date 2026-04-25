import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuditEvent, AuditFilterParams, PageResponse } from '../models';

@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/audit/events`;

  searchEvents(params: AuditFilterParams = {}): Observable<PageResponse<AuditEvent>> {
    let httpParams = new HttpParams();
    if (params.eventType) httpParams = httpParams.set('eventType', params.eventType);
    if (params.subjectType) httpParams = httpParams.set('subjectType', params.subjectType);
    if (params.subjectId) httpParams = httpParams.set('subjectId', params.subjectId);
    if (params.actorId) httpParams = httpParams.set('actorId', params.actorId);
    if (params.from) httpParams = httpParams.set('from', params.from);
    if (params.to) httpParams = httpParams.set('to', params.to);
    if (params.page != null) httpParams = httpParams.set('page', params.page.toString());
    if (params.size != null) httpParams = httpParams.set('size', params.size.toString());

    return this.http.get<PageResponse<AuditEvent>>(this.base, { params: httpParams });
  }

  getEvent(id: string): Observable<AuditEvent> {
    return this.http.get<AuditEvent>(`${this.base}/${id}`);
  }
}
