import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  LegalEntity,
  LegalEntityNameHistory,
  PageResponse,
  EntityFilterParams,
} from '../models';

@Injectable({ providedIn: 'root' })
export class EntityService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/entities`;

  getEntities(params: EntityFilterParams = {}): Observable<PageResponse<LegalEntity>> {
    let httpParams = new HttpParams();
    if (params.type) httpParams = httpParams.set('type', params.type);
    if (params.status) httpParams = httpParams.set('status', params.status);
    if (params.kycStatus) httpParams = httpParams.set('kycStatus', params.kycStatus);
    if (params.search) httpParams = httpParams.set('search', params.search);
    if (params.page != null) httpParams = httpParams.set('page', params.page.toString());
    if (params.size != null) httpParams = httpParams.set('size', params.size.toString());
    if (params.sort) httpParams = httpParams.set('sort', params.sort);

    return this.http.get<PageResponse<LegalEntity>>(this.base, { params: httpParams });
  }

  getEntity(id: string): Observable<LegalEntity> {
    return this.http.get<LegalEntity>(`${this.base}/${id}`);
  }

  createEntity(body: Partial<LegalEntity>): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(this.base, body);
  }

  updateEntity(id: string, body: Partial<LegalEntity>): Observable<LegalEntity> {
    return this.http.put<LegalEntity>(`${this.base}/${id}`, body);
  }

  suspendEntity(id: string): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/suspend`, {});
  }

  dissolveEntity(id: string): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/dissolve`, {});
  }

  reactivateEntity(id: string): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/reactivate`, {});
  }

  getEntityHistory(id: string): Observable<LegalEntityNameHistory[]> {
    return this.http.get<LegalEntityNameHistory[]>(`${this.base}/${id}/name-history`);
  }
}
