import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, switchMap, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SUPPRESS_ERROR_TOAST } from '../interceptors/error-context';

/** The step-up reason the backend's `@RequiresStepUp` names for a read-only support session. */
export const IMPERSONATION_STEP_UP_REASON = 'ADMIN_IMPERSONATION';

/**
 * Extracts the one-time code from `handoffUrl` (`<customer-url>/admin/handoff#code=...&entityId=...`).
 * Returns null when the URL carries none — the caller must then fail visibly rather than navigate on.
 */
export function handoffCodeFrom(handoffUrl: string | null | undefined): string | null {
  const hash = (handoffUrl ?? '').split('#')[1];
  return hash ? new URLSearchParams(hash).get('code') : null;
}

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

  /**
   * Step-up (authenticator code) -> read-only support session -> the one-time handoff code to exchange for
   * the session cookie (`AuthService.enterImpersonation`). The component owns presentation only; the
   * sequence and its wire contract live here, where {@code admin.service.spec.ts} pins them.
   */
  startReadOnlySession(entityId: string, reason: string, totpCode: string, ticket?: string):
      Observable<{ handoffCode: string; session: ImpersonateResponse }> {
    return this.stepUp(totpCode, IMPERSONATION_STEP_UP_REASON).pipe(
      switchMap(r => this.impersonate(entityId, reason, r.stepUpToken, ticket)),
      switchMap(session => {
        const handoffCode = handoffCodeFrom(session.handoffUrl);
        return handoffCode
          ? [{ handoffCode, session }]
          : throwError(() => new Error('The server did not return a handoff code for the support session.'));
      }),
    );
  }

  /**
   * Starts a READ_ONLY support session: needs a step-up token as bearer and a reason of at least 15 characters.
   * Failures are rendered by the caller, so the global "Access denied" toast is suppressed.
   */
  impersonate(entityId: string, reason: string, stepUpToken: string, ticket?: string): Observable<ImpersonateResponse> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<ImpersonateResponse>(this.impersonationUrl,
      { entityId, reason, ticket: ticket || undefined },
      { headers, context: new HttpContext().set(SUPPRESS_ERROR_TOAST, true) });
  }

  /** Exchanges an authenticator code for a step-up token (built-in sign-in). */
  stepUp(totpCode: string, _action?: string): Observable<{ stepUpToken: string }> {
    return this.http.post<{ stepUpToken: string }>(`${environment.apiUrl}/auth/step-up`,
      { code: totpCode, method: 'TOTP' },
      { context: new HttpContext().set(SUPPRESS_ERROR_TOAST, true) });
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
