import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface TwoFactorStatus {
  /** False in local/demo mode — the page then explains 2FA is not active in this environment. */
  applicable: boolean;
  identityModel: 'LOCAL' | 'WORKFORCE_MEMBER' | 'WORKFORCE_GUEST' | 'FEDERATED';
  /** False for a federated identity, whose own organisation manages its MFA. */
  managedHere: boolean;
  registered: boolean;
  methods: string[];
  checkedAt: string | null;
  /** Microsoft's combined security-info page — registration can only happen there. */
  setupUrl: string;
  message: string | null;
}

@Injectable({ providedIn: 'root' })
export class SecurityService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/security`;

  getTwoFactorStatus(): Observable<TwoFactorStatus> {
    return this.http.get<TwoFactorStatus>(`${this.base}/two-factor`);
  }

  /** Forces a fresh read from Microsoft Graph. Server-side throttled per user. */
  refreshTwoFactorStatus(): Observable<TwoFactorStatus> {
    return this.http.post<TwoFactorStatus>(`${this.base}/two-factor/refresh`, {});
  }
}
