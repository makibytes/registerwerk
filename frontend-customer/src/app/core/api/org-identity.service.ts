import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  OrgMemberWalletView,
  OrgRegistrationView,
  PermissionGrantView,
  WalletChallengeView,
} from '../models';

@Injectable({ providedIn: 'root' })
export class OrgIdentityService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/company/org-identity`;

  myOrgs(): Observable<OrgRegistrationView[]> {
    return this.http.get<OrgRegistrationView[]>(this.base);
  }

  myWallets(chainConfigId: string): Observable<OrgMemberWalletView[]> {
    return this.http.get<OrgMemberWalletView[]>(`${this.base}/wallets`, { params: { chainConfigId } });
  }

  createChallenge(chainConfigId: string, walletAddress: string): Observable<WalletChallengeView> {
    return this.http.post<WalletChallengeView>(`${this.base}/wallets/challenge`, {
      chainConfigId,
      walletAddress,
    });
  }

  bindWallet(body: {
    chainConfigId: string;
    walletAddress: string;
    signature: string;
    roles: string[];
    label?: string;
  }): Observable<OrgMemberWalletView> {
    return this.http.post<OrgMemberWalletView>(`${this.base}/wallets`, body);
  }

  removeWallet(walletId: string): Observable<OrgMemberWalletView> {
    return this.http.delete<OrgMemberWalletView>(`${this.base}/wallets/${walletId}`);
  }

  myGrants(chainConfigId: string): Observable<PermissionGrantView[]> {
    return this.http.get<PermissionGrantView[]>(`${this.base}/grants`, { params: { chainConfigId } });
  }

  grantToRole(body: {
    chainConfigId: string;
    roleCode: string;
    permissionDefinitionId: string;
  }): Observable<PermissionGrantView> {
    return this.http.post<PermissionGrantView>(`${this.base}/role-grants`, body);
  }

  revokeRoleGrant(grantId: string): Observable<PermissionGrantView> {
    return this.http.delete<PermissionGrantView>(`${this.base}/role-grants/${grantId}`);
  }

  setRoleRestriction(body: {
    chainConfigId: string;
    permissionDefinitionId: string;
    restricted: boolean;
  }): Observable<PermissionGrantView> {
    return this.http.post<PermissionGrantView>(`${this.base}/role-restriction`, body);
  }
}
