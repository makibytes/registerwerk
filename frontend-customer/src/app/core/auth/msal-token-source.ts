import { Injectable, inject } from '@angular/core';
import type { AccountInfo, PublicClientApplication } from '@azure/msal-browser';
import { Observable, from, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AUTH_CONFIG } from './auth-config';
import { TokenSource } from './token-source';

/**
 * Sign-in through Microsoft Entra ID, driving `@azure/msal-browser` directly.
 *
 * **Why not `@azure/msal-angular`.** It offers `MsalService` (a thin wrapper over
 * `PublicClientApplication`), `MsalGuard` and `MsalInterceptor`. This app has its own
 * `authGuard`/`roleGuard` and its own `authInterceptor`, so the only piece it would use is the
 * wrapper — and taking the dependency would tie the app to msal-angular's Angular version
 * support (v4 tops out at Angular 20; we are on 21). Using msal-browser, which has no Angular
 * peer dependency, sidesteps that entirely — along with msal-angular v5's strict
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

  private pca!: PublicClientApplication;
  private account: AccountInfo | null = null;
  private cachedToken: string | null = null;
  /** Retained from the dynamic import: `instanceof` needs the constructor, not just the type. */
  private interactionRequiredError!: new (...args: never[]) => Error;

  async initialize(): Promise<void> {
    const msal = await import('@azure/msal-browser');
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
    }
  }

  getToken(): string | null {
    return this.cachedToken;
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
  }

  enterImpersonation(_token: string, _entityId: string, _entityName: string): void {
    throw new Error('Impersonation is unavailable with Microsoft Entra sign-in.');
  }

  exitImpersonation(): void {
    // Nothing to restore — impersonation can never have been entered in this mode.
  }

  getImpersonationMeta(): { entityId: string; entityName: string } | null {
    return null;
  }

  supportsImpersonation(): boolean {
    return false;
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
