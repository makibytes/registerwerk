import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { OrgMemberWalletView, OrgRegistrationView, PageResponse } from '../models';

@Injectable({ providedIn: 'root' })
export class OrgIdentityService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/org-identity`;

  register(body: {
    legalEntityId: string;
    chainConfigId: string;
    countryCode?: number | null;
  }): Observable<OrgRegistrationView> {
    return this.http.post<OrgRegistrationView>(`${this.base}/orgs`, body);
  }

  list(params: { status?: string; page?: number; size?: number } = {}): Observable<PageResponse<OrgRegistrationView>> {
    let httpParams = new HttpParams()
      .set('page', params.page ?? 0)
      .set('size', params.size ?? 20);
    if (params.status) {
      httpParams = httpParams.set('status', params.status);
    }
    return this.http.get<PageResponse<OrgRegistrationView>>(`${this.base}/orgs`, { params: httpParams });
  }

  get(id: string): Observable<OrgRegistrationView> {
    return this.http.get<OrgRegistrationView>(`${this.base}/orgs/${id}`);
  }

  suspend(
    id: string,
    reason: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<OrgRegistrationView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<OrgRegistrationView>(`${this.base}/orgs/${id}/suspend`, { reason }, { headers });
  }

  reinstate(
    id: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<OrgRegistrationView> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<OrgRegistrationView>(`${this.base}/orgs/${id}/reinstate`, {}, { headers });
  }

  wallets(orgId: string): Observable<OrgMemberWalletView[]> {
    return this.http.get<OrgMemberWalletView[]>(`${this.base}/orgs/${orgId}/wallets`);
  }

  removeWallet(orgId: string, walletId: string): Observable<OrgMemberWalletView> {
    return this.http.delete<OrgMemberWalletView>(`${this.base}/orgs/${orgId}/wallets/${walletId}`);
  }
}
