import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ImpersonateResponse {
  token: string;
  tokenType: string;
  expiresAt: string;
  entityId: string;
  entityName: string;
}

export interface EntityListItem {
  id: string;
  currentName: string;
  entityNumber: string;
  type: string;
  status: string;
  kycStatus: string;
}

export interface EntityPage {
  content: EntityListItem[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin`;
  private readonly entitiesBase = `${environment.apiUrl}/entities`;

  listEntities(search?: string, page = 0, size = 50): Observable<EntityPage> {
    let params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    if (search) params = params.set('search', search);
    return this.http.get<EntityPage>(this.entitiesBase, { params });
  }

  impersonate(entityId: string): Observable<ImpersonateResponse> {
    return this.http.post<ImpersonateResponse>(`${this.base}/impersonation`, { entityId });
  }
}
