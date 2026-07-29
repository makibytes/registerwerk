import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type AppUserRole =
  | 'REGISTRY_ADMIN'
  | 'AUDIT'
  | 'COMPLIANCE_OFFICER'
  | 'COMPANY_ADMIN'
  | 'ISSUER'
  | 'INVESTOR'
  | 'TRADER';

export interface OperatorUser {
  id: string;
  email: string;
  name: string;
  roles: AppUserRole[];
  entityId: string | null;
  entityName: string | null;
  enabled: boolean;
  lastLoginAt: string | null;
  authProvider: 'LOCAL' | 'ENTRA';
  passwordSetupRequired: boolean;
}

export interface OperatorUserPage {
  content: OperatorUser[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface ImpersonateResponse {
  token: string;
  tokenType: string;
  expiresAt: string;
  entityId: string;
  entityName: string;
  handoffUrl: string;
}

export interface OperatorInviteRequest {
  email: string;
  name: string;
  legalEntityId: string | null;
  roles: AppUserRole[];
}

@Injectable({ providedIn: 'root' })
export class AdminUserService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin`;
  // Not under `base` — impersonation deliberately lives outside the IP-restricted
  // /api/v1/admin/** prefix (see AdminImpersonationController's Javadoc).
  private readonly impersonationUrl = `${environment.apiUrl}/impersonation`;

  listUsers(params: {
    legalEntityId?: string;
    role?: string;
    enabled?: boolean;
    operatorOnly?: boolean;
    search?: string;
    page?: number;
    size?: number;
  }): Observable<OperatorUserPage> {
    let httpParams = new HttpParams();
    if (params.legalEntityId) httpParams = httpParams.set('legalEntityId', params.legalEntityId);
    if (params.role) httpParams = httpParams.set('role', params.role);
    if (params.enabled !== undefined) httpParams = httpParams.set('enabled', String(params.enabled));
    if (params.operatorOnly !== undefined) httpParams = httpParams.set('operatorOnly', String(params.operatorOnly));
    if (params.search) httpParams = httpParams.set('search', params.search);
    httpParams = httpParams.set('page', String(params.page ?? 0));
    httpParams = httpParams.set('size', String(params.size ?? 25));
    return this.http.get<OperatorUserPage>(`${this.base}/users`, { params: httpParams });
  }

  getUser(userId: string): Observable<OperatorUser> {
    return this.http.get<OperatorUser>(`${this.base}/users/${userId}`);
  }

  inviteUser(request: OperatorInviteRequest): Observable<OperatorUser> {
    return this.http.post<OperatorUser>(`${this.base}/users`, request);
  }

  updateRoles(userId: string, roles: AppUserRole[]): Observable<OperatorUser> {
    return this.http.patch<OperatorUser>(`${this.base}/users/${userId}/roles`, { roles });
  }

  enableUser(userId: string): Observable<OperatorUser> {
    return this.http.post<OperatorUser>(`${this.base}/users/${userId}/enable`, {});
  }

  disableUser(userId: string): Observable<OperatorUser> {
    return this.http.post<OperatorUser>(`${this.base}/users/${userId}/disable`, {});
  }

  sendPasswordReset(userId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/users/${userId}/password-reset`, {});
  }

  deleteUser(userId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/users/${userId}`);
  }

  impersonate(entityId: string): Observable<ImpersonateResponse> {
    return this.http.post<ImpersonateResponse>(this.impersonationUrl, { entityId });
  }
}
