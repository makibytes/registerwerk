import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable, tap, map } from 'rxjs';
import { environment } from '../../../environments/environment';

const TOKEN_KEY = 'registerwerk_operator_token';
const ENTITY_ID_KEY = 'registerwerk_operator_entity_id';
const TOKEN_TTL_SECONDS = 8 * 60 * 60;

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

  /**
   * Stub Microsoft login — calls the MSAL OAuth2 flow once IdP is fully wired.
   * TODO: replace stub with MSAL once IdP is wired
   */
  login(): void {
    const issuedAt = Math.floor(Date.now() / 1000);
    const stubToken = btoa(JSON.stringify({
      sub: 'operator-1',
      entityId: 'operator-1',
      roles: ['REGISTRY_ADMIN'],
      iat: issuedAt,
      exp: issuedAt + TOKEN_TTL_SECONDS,
    }));
    localStorage.setItem(TOKEN_KEY, stubToken);
    localStorage.setItem(ENTITY_ID_KEY, 'operator-1');
    this._isAuthenticated$.next(true);
    this.router.navigate(['/dashboard']);
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
      if (parts.length === 3) {
        // Real JWT: decode middle segment with URL-safe base64
        const json = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
        return JSON.parse(json) as Record<string, unknown>;
      }
      // Legacy stub: single base64 blob
      return JSON.parse(atob(token)) as Record<string, unknown>;
    } catch {
      return null;
    }
  }
}
