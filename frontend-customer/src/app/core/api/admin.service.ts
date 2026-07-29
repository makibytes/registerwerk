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
  // Not under `base` — impersonation deliberately lives outside the IP-restricted
  // /api/v1/admin/** prefix (see AdminImpersonationController's Javadoc). This route is
  // requested through Kong from the customer portal, so it must NOT carry the operator-network
  // ip-restriction plugin applied to /api/v1/admin/**.
  private readonly impersonationUrl = `${environment.apiUrl}/impersonation`;

  listEntities(search?: string, page = 0, size = 50): Observable<EntityPage> {
    let params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    if (search) params = params.set('search', search);
    return this.http.get<EntityPage>(this.entitiesBase, { params });
  }

  impersonate(entityId: string): Observable<ImpersonateResponse> {
    return this.http.post<ImpersonateResponse>(this.impersonationUrl, { entityId });
  }
}
