import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

// ── Microsoft Entra 2FA support ───────────────────────────────────────────────

export interface EntraAuthMethod {
  id: string;
  type: string;
  label: string;
  isDefault: boolean;
  /** False for a password or a Temporary Access Pass — neither is individually removable. */
  deletable: boolean;
  createdAt: string | null;
}

export interface EntraMethodsResponse {
  identityModel: 'LOCAL' | 'WORKFORCE_MEMBER' | 'WORKFORCE_GUEST' | 'FEDERATED';
  /** False for a federated identity — every mutating action is refused with a 409. */
  managedHere: boolean;
  /** False for an external B2B guest: Entra will not issue them a Temporary Access Pass. */
  tapSupported: boolean;
  registered: boolean;
  methods: EntraAuthMethod[];
  message: string | null;
}

export interface EntraResetOutcome {
  complete: boolean;
  deleted: string[];
  /** Per-method reasons for anything that could not be removed — a partial reset is visible. */
  failures: string[];
}

export interface TemporaryAccessPassRequest {
  lifetimeMinutes: number;
  usableOnce: boolean;
}

export interface TemporaryAccessPassResponse {
  id: string;
  /** The only copy. Never persisted server-side; Graph will not return it again. */
  value: string;
  startAt: string;
  expiresAt: string;
  lifetimeMinutes: number;
  usableOnce: boolean;
}

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

  // ── Microsoft Entra 2FA support ─────────────────────────────────────────────
  // Every mutating call passes the step-up token as an explicit Authorization header; the
  // auth interceptor leaves an existing header alone, which is the pattern the other 13
  // step-up call sites already use.

  getEntraMethods(userId: string): Observable<EntraMethodsResponse> {
    return this.http.get<EntraMethodsResponse>(`${this.base}/users/${userId}/entra/methods`);
  }

  deleteEntraMethod(
    userId: string,
    type: string,
    methodId: string,
    stepUpToken: string,
  ): Observable<void> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.delete<void>(
      `${this.base}/users/${userId}/entra/methods/${type}/${methodId}`,
      { headers },
    );
  }

  resetEntraMfa(
    userId: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<EntraResetOutcome> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<EntraResetOutcome>(
      `${this.base}/users/${userId}/entra/methods/reset`, {}, { headers },
    );
  }

  revokeEntraSessions(userId: string, stepUpToken: string): Observable<void> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<void>(
      `${this.base}/users/${userId}/entra/revoke-sessions`, {}, { headers },
    );
  }

  /**
   * The response carries the only copy of the pass — it is not stored server-side and Graph
   * will not return it again. Show it once, then discard it.
   */
  issueTemporaryAccessPass(
    userId: string,
    body: TemporaryAccessPassRequest,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<TemporaryAccessPassResponse> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<TemporaryAccessPassResponse>(
      `${this.base}/users/${userId}/entra/temporary-access-pass`, body, { headers },
    );
  }
}
