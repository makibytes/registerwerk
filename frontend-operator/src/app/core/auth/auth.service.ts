import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable, catchError, map, of, shareReplay, tap } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Claims the backend resolves from the session — the httpOnly `rw_session` cookie itself is
 * never readable here by design (see SessionCookieService's Javadoc / the login response's).
 */
interface SessionProfile {
  userId: string;
  roles: string[];
  email: string | null;
  name: string | null;
  entityId: string | null;
  expiresAt: number;
  /**
   * True while the account must change its password first (`must_change_password`): the session is
   * restricted to `POST /auth/change-password`, every other endpoint answers 403 `PASSWORD_CHANGE_REQUIRED`.
   */
  passwordChangeRequired?: boolean;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly _isAuthenticated$ = new BehaviorSubject<boolean>(false);
  private _profile: SessionProfile | null = null;
  private _initialized$: Observable<boolean> | null = null;

  isAuthenticated(): Observable<boolean> {
    return this._isAuthenticated$.asObservable();
  }

  isAuthenticatedSnapshot(): boolean {
    return this._isAuthenticated$.getValue();
  }

  /**
   * Rehydrates auth state from the session cookie via `GET /auth/session` — the token itself
   * moved into an httpOnly cookie (no longer readable/decodable client-side), so this is now
   * the only way to know "am I signed in, and as whom" after a hard page reload. `authGuard`
   * calls this before the first protected navigation; cached (`shareReplay`) so repeated guard
   * checks across a session don't refetch.
   */
  ensureInitialized(): Observable<boolean> {
    if (!this._initialized$) {
      this._initialized$ = this.http.get<SessionProfile>(`${environment.apiUrl}/auth/session`).pipe(
        tap(profile => {
          this._profile = profile;
          this._isAuthenticated$.next(true);
        }),
        map(() => true),
        catchError(() => {
          this._profile = null;
          this._isAuthenticated$.next(false);
          return of(false);
        }),
        shareReplay(1),
      );
    }
    return this._initialized$;
  }

  loginWithCredentials(email: string, password: string): Observable<void> {
    return this.http
      .post<SessionProfile>(`${environment.apiUrl}/public/auth/login`, { email, password })
      .pipe(
        tap(profile => {
          this._profile = profile;
          this._isAuthenticated$.next(true);
          this._initialized$ = of(true); // short-circuits ensureInitialized() for this session
        }),
        map(() => void 0)
      );
  }

  /** True when the signed-in account must set a new password before anything else works. */
  isPasswordChangeRequired(): boolean {
    return this._profile?.passwordChangeRequired === true;
  }

  /**
   * Marks the session as restricted after an endpoint answered 403 `PASSWORD_CHANGE_REQUIRED`
   * (the flag was raised while a session was already open), so the route guards send the user to
   * the change-password screen instead of looping on the failing page.
   */
  markPasswordChangeRequired(): void {
    if (this._profile) {
      this._profile = { ...this._profile, passwordChangeRequired: true };
    }
  }

  /**
   * `POST /auth/change-password`: on success the backend revokes the restricted token and answers
   * with a fresh unrestricted session (cookie + profile), which replaces the cached one.
   */
  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http
      .post<SessionProfile>(`${environment.apiUrl}/auth/change-password`, { currentPassword, newPassword })
      .pipe(
        tap(profile => {
          this._profile = { ...profile, passwordChangeRequired: false };
          this._isAuthenticated$.next(true);
          this._initialized$ = of(true);
        }),
        map(() => void 0)
      );
  }

  logout(): void {
    this.http.post(`${environment.apiUrl}/public/auth/logout`, {}).subscribe({
      next: () => this.finishLogout(),
      error: () => this.finishLogout(), // still drop client-side state if the call itself fails (e.g. offline)
    });
  }

  /**
   * Drops only the browser-side view of the session. Used for a server-reported 401: calling
   * logout() from the HTTP error interceptor would make another HTTP request through that same
   * interceptor and can recurse indefinitely when the logout endpoint also returns 401.
   */
  clearSession(): void {
    this._profile = null;
    this._initialized$ = null;
    this._isAuthenticated$.next(false);
  }

  private finishLogout(): void {
    this.clearSession();
    this.router.navigate(['/login']);
  }

  getUserRoles(): string[] {
    return this._profile?.roles ?? [];
  }

  getUserEmail(): string | null {
    return this._profile?.email ?? null;
  }

  getUserName(): string | null {
    return this._profile?.name ?? null;
  }

  hasRole(role: string): boolean {
    return this.getUserRoles().includes(role);
  }

  getUserId(): string | null {
    return this._profile?.userId ?? null;
  }
}
