import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import type { AccountInfo, PublicClientApplication } from '@azure/msal-browser';
import { SUPPRESS_ERROR_TOAST } from '@registerwerk/ui';
import { Observable, firstValueFrom, from, of, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { AUTH_CONFIG } from './auth-config';
import { TokenSource } from './token-source';

/** Entra tokens are never persisted here (MSAL owns that in its own sessionStorage cache) —
 *  only decoded on demand for `getProfile()`, the same way the app has always read claims. */
function decodePayload(token: string): Record<string, unknown> {
  const base64 = token.split('.')[1];
  if (!base64) throw new Error('Invalid token');
  const json = atob(base64.replace(/-/g, '+').replace(/_/g, '/'));
  return JSON.parse(json) as Record<string, unknown>;
}

/**
 * Sign-in through Microsoft Entra ID, driving `@azure/msal-browser` directly.
 *
 * **Why not `@azure/msal-angular`.** It offers `MsalService` (a thin wrapper over
 * `PublicClientApplication`), `MsalGuard` and `MsalInterceptor`. This app has its own
 * `authGuard`/`roleGuard` and its own `authInterceptor`, so the only piece it would use is the
 * wrapper — and taking the dependency would unnecessarily couple upgrades to msal-angular's
 * Angular peer-version range. Using msal-browser, which has no Angular peer dependency,
 * sidesteps that entirely — along with msal-angular's strict
 * `protectedResourceMap` matching, a well-known source of silent 401s.
 *
 * **Why MSAL is imported dynamically.** `app.config.ts` has to reference this class statically to
 * pick a provider, so a top-level `import { PublicClientApplication }` would drag ~255 kB of MSAL
 * into the initial bundle of every deployment — including local mode, which never uses it, and
 * which is how this app is run in development. Types are imported with `import type` (erased at
 * compile time) and the module is pulled in at `initialize()`, so MSAL lands in a lazy chunk that
 * is only fetched when Entra sign-in is actually configured.
 */
@Injectable()
export class MsalTokenSource extends TokenSource {
  private readonly config = inject(AUTH_CONFIG);
  private readonly http = inject(HttpClient);

  private pca!: PublicClientApplication;
  private account: AccountInfo | null = null;
  private cachedToken: string | null = null;
  /**
   * The account as the backend resolves it (`GET /auth/session`): roles and legal entity come from the
   * `app_user` row, never from the IdP, so the Entra access token itself carries neither. Null until
   * loaded, or when the backend has no account for this sign-in yet.
   */
  private session: Record<string, unknown> | null = null;
  /** Retained from the dynamic import: `instanceof` needs the constructor, not just the type. */
  private interactionRequiredError!: new (...args: never[]) => Error;

  /** Seam for the tests: the dynamic import is what keeps MSAL out of the initial bundle. */
  protected loadMsal(): Promise<typeof import('@azure/msal-browser')> {
    return import('@azure/msal-browser');
  }

  async initialize(): Promise<void> {
    const msal = await this.loadMsal();
    this.interactionRequiredError = msal.InteractionRequiredAuthError;

    this.pca = new msal.PublicClientApplication({
      auth: {
        clientId: this.config.clientId,
        authority: this.config.authority,
        redirectUri: `${window.location.origin}/`,
        // Declares that this client understands OAuth2 claims challenges. Without it Entra never
        // adds `acrs` opportunistically, so every step-up action costs a full redirect — a
        // failure mode that looks exactly like an application bug.
        clientCapabilities: ['CP1'],
      },
      cache: {
        // sessionStorage keeps the token out of other tabs and clears it when the tab closes.
        cacheLocation: 'sessionStorage',
      },
    });

    await this.pca.initialize();

    // `navigateToLoginRequestUrl` moved here from `auth` in msal-browser v5. Off, because we
    // restore the intended route through the Angular router — letting MSAL navigate during
    // bootstrap races the app initializer.
    const redirectResult = await this.pca.handleRedirectPromise({ navigateToLoginRequestUrl: false });
    if (redirectResult?.account) {
      this.pca.setActiveAccount(redirectResult.account);
      this.cachedToken = redirectResult.accessToken || null;
    }

    this.account = this.pca.getActiveAccount() ?? this.pca.getAllAccounts()[0] ?? null;
    if (this.account) {
      this.pca.setActiveAccount(this.account);
      // Prime the cache so guards, which are synchronous, see a signed-in user on first paint.
      await this.refreshSilently();
      await this.loadSession();
    }
  }

  getToken(): string | null {
    return this.cachedToken;
  }

  getProfile(): Record<string, unknown> | null {
    if (!this.cachedToken) return null;
    try {
      const claims = decodePayload(this.cachedToken);
      return this.session ? { ...claims, ...this.session } : claims;
    } catch {
      return null;
    }
  }

  acquireToken$(): Observable<string | null> {
    if (!this.account) {
      return of(null);
    }
    return from(this.pca.acquireTokenSilent({ scopes: this.config.scopes, account: this.account })).pipe(
      map(result => {
        this.cachedToken = result.accessToken;
        return this.cachedToken;
      }),
      catchError((err: unknown) => {
        if (err instanceof this.interactionRequiredError) {
          // The refresh token is gone, or Conditional Access wants the user back. Only a full
          // redirect can resolve it; this navigates away and never emits.
          void this.pca.acquireTokenRedirect({ scopes: this.config.scopes, account: this.account! });
          return of(null);
        }
        console.error('[auth] Silent token acquisition failed', err);
        return of(null);
      })
    );
  }

  acquireTokenWithClaims(claimsBase64: string): void {
    // Entra expects the decoded claims request; the challenge transports it base64-encoded.
    let claims: string;
    try {
      claims = atob(claimsBase64);
    } catch {
      console.error('[auth] Malformed claims challenge — cannot decode', claimsBase64);
      return;
    }
    // A claims-bearing request deliberately bypasses the token cache, so the new token really
    // does reflect the freshly satisfied Conditional Access authentication context.
    this.cachedToken = null;
    void this.pca.acquireTokenRedirect({
      scopes: this.config.scopes,
      account: this.account ?? undefined,
      claims,
    });
  }

  isAuthenticated(): boolean {
    return this.account !== null && this.cachedToken !== null;
  }

  login(): void {
    void this.pca.loginRedirect({ scopes: this.config.scopes });
  }

  loginWithCredentials(_email: string, _password: string): Observable<void> {
    return throwError(() => new Error('Password sign-in is unavailable with Microsoft Entra sign-in.'));
  }

  logout(): void {
    this.cachedToken = null;
    void this.pca.logoutRedirect({ account: this.account ?? undefined });
  }

  // ── Local-only operations ───────────────────────────────────────────────────
  // Entra owns the session; the app cannot mint or swap tokens. These throw rather than no-op
  // so a caller that has not been updated fails visibly instead of appearing to succeed.

  setToken(_token: string): void {
    throw new Error('Tokens are issued by Microsoft Entra ID and cannot be set by the application.');
  }

  clearToken(): void {
    // Not an error: the error interceptor calls this on a genuine 401 before redirecting to
    // sign-in, and dropping the cached token is exactly the right local effect.
    this.cachedToken = null;
    this.session = null;
  }

  enterImpersonation(_code: string, _entityId: string, _entityName: string): Observable<void> {
    return throwError(() => new Error('Impersonation is unavailable with Microsoft Entra sign-in.'));
  }

  exitImpersonation(): Observable<void> {
    // Nothing to restore — impersonation can never have been entered in this mode.
    return of(void 0);
  }

  getImpersonationMode(): 'READ_ONLY' | 'ACT_ON_BEHALF' | null {
    return null;
  }

  getImpersonationMeta(): { entityId: string; entityName: string } | null {
    return null;
  }

  supportsImpersonation(): boolean {
    return false;
  }

  /**
   * Fetches the backend's view of the signed-in account. The answer to this probe belongs to this method,
   * so its failure (for example 403 for an account that has not been provisioned yet) neither toasts nor
   * blocks sign-in: the token claims remain the fallback, as before.
   */
  private async loadSession(): Promise<void> {
    if (!this.cachedToken) return;
    try {
      this.session = await firstValueFrom(this.http.get<Record<string, unknown>>(
        `${environment.apiUrl}/auth/session`,
        { context: new HttpContext().set(SUPPRESS_ERROR_TOAST, true) }));
    } catch {
      this.session = null;
    }
  }

  private async refreshSilently(): Promise<void> {
    try {
      const result = await this.pca.acquireTokenSilent({
        scopes: this.config.scopes,
        account: this.account!,
      });
      this.cachedToken = result.accessToken;
    } catch {
      // Expected when the session has lapsed. Leave the token null; the guard sends the user to
      // sign in, rather than the app failing to start.
      this.cachedToken = null;
    }
  }
}
