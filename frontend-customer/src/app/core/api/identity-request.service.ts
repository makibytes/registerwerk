import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface IdentityRegistrationResponse {
  status: string;
  entityId: string | null;
  assetId: string;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class IdentityRequestService {
  private readonly http = inject(HttpClient);

  requestIdentityRegistration(body: {
    assetId: string;
    deploymentId: string;
    walletAddress: string;
    note?: string;
  }): Observable<IdentityRegistrationResponse> {
    return this.http.post<IdentityRegistrationResponse>(
      `${environment.apiUrl}/me/identity-registration-requests`,
      body,
    );
  }
}
