import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CompanyUser, IdpSettings, LegalEntity } from '../models';

@Injectable({ providedIn: 'root' })
export class CompanyService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/company`;

  // ── Entity ─────────────────────────────────────────────────────────────────

  getMyEntity(): Observable<LegalEntity> {
    return this.http.get<LegalEntity>(`${this.base}/me`);
  }

  // ── Users ──────────────────────────────────────────────────────────────────

  getEntityUsers(): Observable<CompanyUser[]> {
    return this.http.get<CompanyUser[]>(`${this.base}/users`);
  }

  inviteUser(body: { email: string; name: string; role: string }): Observable<CompanyUser> {
    return this.http.post<CompanyUser>(`${this.base}/users/invite`, body);
  }

  removeUser(userId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/users/${userId}`);
  }

  // ── IdP settings ───────────────────────────────────────────────────────────

  getIdpSettings(): Observable<IdpSettings> {
    return this.http.get<IdpSettings>(`${this.base}/idp`);
  }

  saveIdpSettings(body: IdpSettings): Observable<IdpSettings> {
    return this.http.put<IdpSettings>(`${this.base}/idp`, body);
  }
}
