import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable, tap, map } from 'rxjs';
import { environment } from '../../../environments/environment';

const TOKEN_KEY = 'registerwerk_operator_token';
const ENTITY_ID_KEY = 'registerwerk_operator_entity_id';

interface LoginApiResponse {
  token: string;
  tokenType: string;
  userId: string;
  roles: string[];
  expiresAt: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _isAuthenticated$ = new BehaviorSubject<boolean>(
    this._hasValidToken()
  );

  constructor(
    private readonly router: Router,
    private readonly http: HttpClient
  ) {}

  isAuthenticated(): Observable<boolean> {
    return this._isAuthenticated$.asObservable();
  }

  isAuthenticatedSnapshot(): boolean {
    return this._isAuthenticated$.getValue();
  }

  /**
   * Authenticates against the backend's /public/auth/login endpoint.
   * Stores the returned JWT and entityId in localStorage.
   */
  loginWithCredentials(email: string, password: string): Observable<void> {
    return this.http
      .post<LoginApiResponse>(`${environment.apiUrl}/public/auth/login`, { email, password })
      .pipe(
        tap(res => {
          localStorage.setItem(TOKEN_KEY, res.token);
          const payload = this._decodeJwtPayload(res.token);
          const entityId = (payload?.['entityId'] as string) ?? res.userId;
          localStorage.setItem(ENTITY_ID_KEY, entityId);
          this._isAuthenticated$.next(true);
        }),
        map(() => void 0)
      );
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ENTITY_ID_KEY);
    this._isAuthenticated$.next(false);
    this.router.navigate(['/login']);
  }

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  getCurrentEntityId(): string | null {
    return localStorage.getItem(ENTITY_ID_KEY);
  }

  private _hasValidToken(): boolean {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      return false;
    }
    try {
      const payload = this._parseTokenPayload(token);
      const now = Math.floor(Date.now() / 1000);
      return typeof payload?.['exp'] === 'number' && (payload['exp'] as number) > now;
    } catch {
      return false;
    }
  }

  /** Decodes the payload of a real JWT (header.payload.signature). */
  private _decodeJwtPayload(token: string): Record<string, unknown> | null {
    return this._parseTokenPayload(token);
  }

  /**
   * Handles both real JWTs (three base64url segments) and the legacy stub format
   * (a single base64 blob) so the Entra stub branch keeps working.
   */
  private _parseTokenPayload(token: string): Record<string, unknown> | null {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        return null;
      }

      const base64Url = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      const padded = base64Url.padEnd(base64Url.length + ((4 - (base64Url.length % 4)) % 4), '=');
      return JSON.parse(atob(padded)) as Record<string, unknown>;
    } catch {
      return null;
    }
  }
}
