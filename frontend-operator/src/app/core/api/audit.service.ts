import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuditEvent, AuditFilterParams, ChainVerificationResult, PageResponse } from '../models';

@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/audit/events`;
  private readonly auditBase = `${environment.apiUrl}/audit`;

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

  /** KYC/jurisdiction-approval overrides — REGISTRY_ADMIN decisions overriding an automated KYC outcome. */
  kycOverrideReport(params: {
    jurisdiction?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  } = {}): Observable<PageResponse<AuditEvent>> {
    let httpParams = new HttpParams();
    if (params.jurisdiction) httpParams = httpParams.set('jurisdiction', params.jurisdiction);
    if (params.from) httpParams = httpParams.set('from', params.from);
    if (params.to) httpParams = httpParams.set('to', params.to);
    if (params.page != null) httpParams = httpParams.set('page', params.page.toString());
    if (params.size != null) httpParams = httpParams.set('size', params.size.toString());

    return this.http.get<PageResponse<AuditEvent>>(`${this.auditBase}/reports/kyc-overrides`, { params: httpParams });
  }

  getEvent(id: string): Observable<AuditEvent> {
    return this.http.get<AuditEvent>(`${this.base}/${id}`);
  }

  chainStatus(): Observable<ChainVerificationResult> {
    return this.http.get<ChainVerificationResult>(`${this.auditBase}/chain/status`);
  }

  verifyChainNow(): Observable<ChainVerificationResult> {
    return this.http.post<ChainVerificationResult>(`${this.auditBase}/chain/verify`, {});
  }
}
