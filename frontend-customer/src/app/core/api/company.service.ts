import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CompanyExternalReferenceRecord,
  CompanyUser,
  ExternalReferenceSubjectType,
  IdpSettings,
  LegalEntity,
  PublicUserActionTokenInfo,
  UserRole,
} from '../models';

@Injectable({ providedIn: 'root' })
export class CompanyService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/company`;

  // ── Entity ─────────────────────────────────────────────────────────────────

  getMyEntity(): Observable<LegalEntity> {
    return this.http.get<LegalEntity>(`${this.base}/me`);
  }

  listExternalIds(subjectType?: ExternalReferenceSubjectType): Observable<CompanyExternalReferenceRecord[]> {
    let params = new HttpParams();
    if (subjectType) {
      params = params.set('subjectType', subjectType);
    }
    return this.http.get<CompanyExternalReferenceRecord[]>(`${this.base}/external-ids`, { params });
  }

  lookupExternalIds(
    externalId: string,
    subjectType?: ExternalReferenceSubjectType
  ): Observable<CompanyExternalReferenceRecord[]> {
    let params = new HttpParams().set('externalId', externalId);
    if (subjectType) {
      params = params.set('subjectType', subjectType);
    }
    return this.http.get<CompanyExternalReferenceRecord[]>(`${this.base}/external-ids/lookup`, { params });
  }

  saveExternalId(
    subjectType: ExternalReferenceSubjectType,
    subjectId: string,
    externalId: string
  ): Observable<{ subjectType: ExternalReferenceSubjectType; subjectId: string; externalId: string; updatedAt: string }> {
    return this.http.put<{ subjectType: ExternalReferenceSubjectType; subjectId: string; externalId: string; updatedAt: string }>(
      `${this.base}/external-ids/${subjectType}/${subjectId}`,
      { externalId }
    );
  }

  deleteExternalId(subjectType: ExternalReferenceSubjectType, subjectId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/external-ids/${subjectType}/${subjectId}`);
  }

  // ── Users ──────────────────────────────────────────────────────────────────

  getEntityUsers(): Observable<CompanyUser[]> {
    return this.http.get<CompanyUser[]>(`${this.base}/users`);
  }

  inviteUser(body: { email: string; name: string; roles: UserRole[] }): Observable<CompanyUser> {
    return this.http.post<CompanyUser>(`${this.base}/users/invite`, body);
  }

  updateUserRoles(userId: string, roles: UserRole[]): Observable<CompanyUser> {
    return this.http.patch<CompanyUser>(`${this.base}/users/${userId}/roles`, { roles });
  }

  disableUser(userId: string): Observable<CompanyUser> {
    return this.http.post<CompanyUser>(`${this.base}/users/${userId}/disable`, {});
  }

  enableUser(userId: string): Observable<CompanyUser> {
    return this.http.post<CompanyUser>(`${this.base}/users/${userId}/enable`, {});
  }

  sendPasswordReset(userId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/users/${userId}/password-reset`, {});
  }

  removeUser(userId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/users/${userId}`);
  }

  // ── IdP settings ───────────────────────────────────────────────────────────

  getIdpSettings(): Observable<IdpSettings> {
    return this.http.get<IdpSettings>(`${this.base}/idp`);
  }

  saveIdpSettings(body: { issuerUrl: string; clientId: string; clientSecret: string }): Observable<IdpSettings> {
    return this.http.put<IdpSettings>(`${this.base}/idp`, body);
  }

  getRegistrationInfo(token: string): Observable<PublicUserActionTokenInfo> {
    return this.http.get<PublicUserActionTokenInfo>(`${environment.apiUrl}/public/company-users/registration/${token}`);
  }

  completeRegistration(token: string, name: string, password: string): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/public/company-users/registration/complete`, {
      token,
      name,
      password,
    });
  }

  getPasswordResetInfo(token: string): Observable<PublicUserActionTokenInfo> {
    return this.http.get<PublicUserActionTokenInfo>(`${environment.apiUrl}/public/company-users/password-reset/${token}`);
  }

  completePasswordReset(token: string, password: string): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/public/company-users/password-reset/complete`, {
      token,
      password,
    });
  }
}
