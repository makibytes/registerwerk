import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  EcosystemTrustedIssuerView,
  PermissionDefinitionView,
  PermissionGrantView,
} from '../models';

@Injectable({ providedIn: 'root' })
export class PermissionService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/permissions`;

  list(): Observable<PermissionDefinitionView[]> {
    return this.http.get<PermissionDefinitionView[]>(this.base);
  }

  create(body: { code: string; name: string; description?: string }): Observable<PermissionDefinitionView> {
    return this.http.post<PermissionDefinitionView>(this.base, body);
  }

  grants(definitionId: string): Observable<PermissionGrantView[]> {
    return this.http.get<PermissionGrantView[]>(`${this.base}/${definitionId}/grants`);
  }

  grantToOrg(
    definitionId: string,
    orgRegistrationId: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<PermissionGrantView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<PermissionGrantView>(
      `${this.base}/${definitionId}/org-grants`,
      { orgRegistrationId },
      { headers },
    );
  }

  revokeGrant(
    grantId: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<PermissionGrantView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.delete<PermissionGrantView>(`${this.base}/org-grants/${grantId}`, { headers });
  }

  trustedIssuers(): Observable<EcosystemTrustedIssuerView[]> {
    return this.http.get<EcosystemTrustedIssuerView[]>(`${this.base}/trusted-issuers`);
  }

  addTrustedIssuer(
    body: { chainConfigId: string; issuerAddress: string; claimTopics: number[]; legalEntityId?: string },
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<EcosystemTrustedIssuerView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<EcosystemTrustedIssuerView>(`${this.base}/trusted-issuers`, body, { headers });
  }

  removeTrustedIssuer(
    issuerId: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<EcosystemTrustedIssuerView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.delete<EcosystemTrustedIssuerView>(`${this.base}/trusted-issuers/${issuerId}`, { headers });
  }
}
