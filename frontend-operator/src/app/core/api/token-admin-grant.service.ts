import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TokenAdminGrant, TokenAdminGrantRequest } from '../models';

/**
 * ASSET_TOKEN_ADMIN grant management — the delegatable permission gating
 * forcedTransfer/forcedApprove/forceBurn beyond REGISTRY_ADMIN. Deliberately separate
 * from the unrelated orgidentity ecosystem permission API (`permission.service.ts`).
 */
@Injectable({ providedIn: 'root' })
export class TokenAdminGrantService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}`;

  listForAsset(assetId: string): Observable<TokenAdminGrant[]> {
    return this.http.get<TokenAdminGrant[]>(`${this.base}/assets/${assetId}/token-admin-grants`);
  }

  listForEntity(entityId: string): Observable<TokenAdminGrant[]> {
    return this.http.get<TokenAdminGrant[]>(`${this.base}/entities/${entityId}/token-admin-grants`);
  }

  grantForAsset(
    assetId: string, body: TokenAdminGrantRequest, stepUpToken: string, dualControlToken: string,
  ): Observable<TokenAdminGrant> {
    return this.http.post<TokenAdminGrant>(
      `${this.base}/assets/${assetId}/token-admin-grants`, body, { headers: this.stepUpHeaders(stepUpToken, dualControlToken) });
  }

  grantForEntity(
    entityId: string, body: TokenAdminGrantRequest, stepUpToken: string, dualControlToken: string,
  ): Observable<TokenAdminGrant> {
    return this.http.post<TokenAdminGrant>(
      `${this.base}/entities/${entityId}/token-admin-grants`, body, { headers: this.stepUpHeaders(stepUpToken, dualControlToken) });
  }

  revokeForAsset(
    assetId: string, grantId: string, reason: string, stepUpToken: string, dualControlToken: string,
  ): Observable<TokenAdminGrant> {
    return this.http.post<TokenAdminGrant>(
      `${this.base}/assets/${assetId}/token-admin-grants/${grantId}/revoke`, { reason },
      { headers: this.stepUpHeaders(stepUpToken, dualControlToken) });
  }

  revokeForEntity(
    entityId: string, grantId: string, reason: string, stepUpToken: string, dualControlToken: string,
  ): Observable<TokenAdminGrant> {
    return this.http.post<TokenAdminGrant>(
      `${this.base}/entities/${entityId}/token-admin-grants/${grantId}/revoke`, { reason },
      { headers: this.stepUpHeaders(stepUpToken, dualControlToken) });
  }

  private stepUpHeaders(stepUpToken: string, dualControlToken: string): HttpHeaders {
    return new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
  }
}
