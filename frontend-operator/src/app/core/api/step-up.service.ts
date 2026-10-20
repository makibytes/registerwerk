import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { SUPPRESS_ERROR_TOAST } from '@registerwerk/ui';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface StepUpTokenResponse {
  stepUpToken: string;
}

@Injectable({ providedIn: 'root' })
export class StepUpService {
  private readonly http = inject(HttpClient);

  /** Every call here is answered in the dialog that made it (a wrong code is a 403), never by a global toast. */
  private readonly inline = { context: new HttpContext().set(SUPPRESS_ERROR_TOAST, true) };

  /**
   * Exchange a TOTP code for a short-lived step-up JWT (acr=stepup).
   * The returned token must be used as the Authorization Bearer for @RequiresStepUp endpoints.
   *
   * Your OWN step-up token (the initiator's) is minted with only `totpCode`. Passing `action`
   * makes this a dual-control APPROVER token, and the backend then requires `target` too: the
   * exact `"METHOD /api/v1/path[?query]"` of the call being approved (and `targetBody`, the JSON
   * body, for body-bound reasons such as mint/burn/forced transfer). The approver token lives
   * 5 minutes, works for that one request and is burnt by a single use, even a failed one.
   */
  issueToken(totpCode: string, action?: string, target?: string, targetBody?: unknown): Observable<StepUpTokenResponse> {
    return this.http.post<StepUpTokenResponse>(`${environment.apiUrl}/auth/step-up`, {
      code: totpCode,
      method: 'TOTP',
      action: action ?? undefined,
      target: action ? (target ?? undefined) : undefined,
      targetBody: action && targetBody !== undefined ? targetBody : undefined,
    }, this.inline);
  }

  /** Starts TOTP enrolment; re-proves the account password. IdP-managed accounts get 403. */
  enroll(currentPassword: string): Observable<{ secret: string; otpauthUri: string }> {
    return this.http.post<{ secret: string; otpauthUri: string }>(
      `${environment.apiUrl}/auth/step-up/enroll`, { currentPassword }, this.inline);
  }

  confirmEnrollment(code: string): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/auth/step-up/enroll/confirm`, { code }, this.inline);
  }

  /** Removes the authenticator (needs a current code); ends all sessions of the user. */
  disenroll(code: string): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/auth/step-up/disenroll`, { code }, this.inline);
  }
}
