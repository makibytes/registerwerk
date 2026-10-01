import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** No bearer token any more: the handoff URL carries a one-time code (`#code=...`) exchanged for a session cookie. */
export interface ImpersonateResponse {
  sessionId: string;
  mode: 'READ_ONLY' | 'ACT_ON_BEHALF';
  expiresAt: string;
  entityId: string;
  entityName: string;
  handoffUrl: string;
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

  /** Starts a READ_ONLY support session: needs a step-up token as bearer and a reason of at least 15 characters. */
  impersonate(entityId: string, reason: string, stepUpToken: string, ticket?: string): Observable<ImpersonateResponse> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<ImpersonateResponse>(this.impersonationUrl,
      { entityId, reason, ticket: ticket || undefined }, { headers });
  }

  /** Exchanges an authenticator code for a step-up token (built-in sign-in). */
  stepUp(totpCode: string, action: string): Observable<{ stepUpToken: string }> {
    return this.http.post<{ stepUpToken: string }>(`${environment.apiUrl}/auth/step-up`,
      { code: totpCode, method: 'TOTP', action });
  }
}

export interface ImpersonationSessionView {
  id: string;
  mode: 'READ_ONLY' | 'ACT_ON_BEHALF';
  reason: string;
  ticketRef: string | null;
  actorId: string;
  approverId: string | null;
  startedAt: string;
  expiresAt: string;
  endedAt: string | null;
  endReason: string | null;
}
