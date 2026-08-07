import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, firstValueFrom, map, of, switchMap, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TokenSource } from './token-source';

/** Shape of `POST /public/auth/login|impersonate` and `GET /auth/session` responses. */
interface SessionProfile {
  userId: string;
  roles: string[];
  email: string | null;
  name: string | null;
  entityId: string | null;
  entityName: string | null;
  impersonating: boolean;
  expiresAt: number;
  /** Lets this satisfy `Record<string, unknown>` for `getProfile()` — not otherwise used. */
  [key: string]: unknown;
}

/**
 * The built-in login path. The backend mints an HS256 token and sets it as an httpOnly
 * `rw_session` cookie — the browser attaches it automatically, and it is never visible to page
 * JavaScript (ledger finding: "Frontend JWTs are stored in localStorage"). This class holds
 * only the non-secret profile claims the backend returns alongside that cookie, refetched via
 * `GET /api/v1/auth/session` on every hard page load since there is no longer a token here to
 * decode.
 */
@Injectable()
export class CookieTokenSource extends TokenSource {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private profile: SessionProfile | null = null;

  async initialize(): Promise<void> {
    try {
      this.profile = await firstValueFrom(
        this.http.get<SessionProfile>(`${environment.apiUrl}/auth/session`)
      );
    } catch {
      this.profile = null;
    }
  }

  getToken(): string | null {
    // The token itself is never readable here by design — see this class's Javadoc-equivalent
    // above. Nothing in the app reads getToken() outside AuthService/the interceptor, both of
    // which use isAuthenticated()/getProfile()/acquireToken$() instead.
    return null;
  }

  getProfile(): Record<string, unknown> | null {
    return this.profile;
  }

  acquireToken$(): Observable<string | null> {
    return of(null); // nothing to attach — the cookie authenticates the request on its own
  }

  acquireTokenWithClaims(_claimsBase64: string): void {
    // A claims challenge is an Entra concept. In local mode step-up is proved with a TOTP code
    // exchanged for an `acr=stepup` token, so reaching here means the backend and frontend
    // disagree about the auth mode — worth a console warning rather than a silent no-op.
    console.warn(
      '[auth] Received an OAuth2 claims challenge while in local auth mode. ' +
        'Step-up in this mode uses POST /auth/step-up with a TOTP code.'
    );
  }

  isAuthenticated(): boolean {
    return this.profile !== null;
  }

  setToken(_token: string): void {
    console.warn('[auth] setToken() is a no-op under cookie-based local auth — the backend owns the session cookie.');
  }

  clearToken(): void {
    this.profile = null;
  }

  login(): void {
    this.router.navigate(['/login']);
  }

  loginWithCredentials(email: string, password: string): Observable<void> {
    return this.http
      .post<SessionProfile>(`${environment.apiUrl}/public/auth/login`, { email, password })
      .pipe(
        tap(profile => { this.profile = profile; }),
        map(() => void 0)
      );
  }

  logout(): void {
    this.http.post(`${environment.apiUrl}/public/auth/logout`, {}).subscribe({
      next: () => this.finishLogout(),
      error: () => this.finishLogout(), // still drop client-side state if the call itself fails
    });
  }

  private finishLogout(): void {
    this.profile = null;
    this.router.navigate(['/login']);
  }

  enterImpersonation(token: string, _entityId: string, _entityName: string): Observable<void> {
    return this.http
      .post<SessionProfile>(`${environment.apiUrl}/public/auth/impersonate`, { token })
      .pipe(
        tap(profile => { this.profile = profile; }),
        map(() => void 0)
      );
  }

  exitImpersonation(): Observable<void> {
    return this.http.post(`${environment.apiUrl}/auth/exit-impersonation`, {}).pipe(
      // The backend either restored the admin's own session or logged out entirely — either
      // way, re-fetch /auth/session rather than guessing which, so `profile` reflects the
      // cookie the response just set before this Observable completes (a fire-and-forget
      // refetch here would let a caller navigate and re-render on stale profile data).
      switchMap(() => this.http.get<SessionProfile>(`${environment.apiUrl}/auth/session`)),
      tap(profile => { this.profile = profile; }),
      map(() => void 0),
      catchError(() => {
        this.profile = null;
        return of(void 0);
      }),
    );
  }

  getImpersonationMeta(): { entityId: string; entityName: string } | null {
    if (!this.profile?.impersonating || !this.profile.entityId) return null;
    return { entityId: this.profile.entityId, entityName: this.profile.entityName ?? '' };
  }

  supportsImpersonation(): boolean {
    return true;
  }
}
