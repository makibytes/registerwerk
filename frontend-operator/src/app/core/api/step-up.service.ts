import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface StepUpTokenResponse {
  stepUpToken: string;
}

@Injectable({ providedIn: 'root' })
export class StepUpService {
  private readonly http = inject(HttpClient);

  /**
   * Exchange a TOTP code for a short-lived step-up JWT (acr=stepup).
   * The returned token must be used as the Authorization Bearer for @RequiresStepUp endpoints.
   *
   * @param action the exact `@RequiresStepUp(reason=...)` value of the action this token will
   *   be used to approve. Required when minting a token that will be
   *   used as a dual-control APPROVER's token — the backend's StepUpTokenValidator rejects any
   *   dual-control approval whose token has no `stepup_scope` claim, or one that doesn't exactly
   *   match the target action's reason string. Previously this was never sent, so every
   *   dual-control (requireSecondApprover=true) action failed unconditionally.
   */
  issueToken(totpCode: string, action?: string): Observable<StepUpTokenResponse> {
    return this.http.post<StepUpTokenResponse>(`${environment.apiUrl}/auth/step-up`, {
      code: totpCode,
      method: 'TOTP',
      action: action ?? undefined,
    });
  }
}
