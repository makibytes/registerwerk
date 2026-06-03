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
   */
  issueToken(totpCode: string): Observable<StepUpTokenResponse> {
    return this.http.post<StepUpTokenResponse>(`${environment.apiUrl}/auth/step-up`, {
      code: totpCode,
      method: 'TOTP',
    });
  }
}
