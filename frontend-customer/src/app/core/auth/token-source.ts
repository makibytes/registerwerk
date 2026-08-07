import { Observable } from 'rxjs';

/**
 * Where the app's bearer token comes from. Two implementations exist —
 * `CookieTokenSource` for built-in password login and `MsalTokenSource` for Entra — and
 * `app.config.ts` picks one at bootstrap based on the fetched `AuthConfig`.
 *
 * `AuthService` delegates here, so guards, `getEntityId()`, the impersonation bar and every
 * feature component keep working unchanged regardless of which is active.
 */
export abstract class TokenSource {
  /** Runs once during app initialisation, before the router activates. */
  abstract initialize(): Promise<void>;

  /**
   * Cached token, synchronously — for the interceptor's `Authorization` header (Entra) and
   * legacy claim-inspection call sites. Under `CookieTokenSource` this is always null: the
   * session lives in an httpOnly cookie the browser attaches automatically, invisible to JS by
   * design (ledger finding: "Frontend JWTs are stored in localStorage"). Use `getProfile()` for
   * claims — it works under both implementations.
   */
  abstract getToken(): string | null;

  /**
   * Decoded claims for the current session (roles, entityId, email, name, imp), regardless of
   * whether a raw token is ever exposed to JS. `MsalTokenSource` decodes its cached access
   * token; `CookieTokenSource` returns what `GET /api/v1/auth/session` last returned.
   */
  abstract getProfile(): Record<string, unknown> | null;

  /**
   * A token fit to send, acquiring or refreshing one if needed. The HTTP interceptor uses this
   * rather than `getToken()` so an Entra token can be silently renewed mid-session.
   * `CookieTokenSource` always resolves null — nothing to attach, the cookie authenticates the
   * request on its own.
   */
  abstract acquireToken$(): Observable<string | null>;

  /**
   * Re-authenticate to satisfy an OAuth2 claims challenge (`error="insufficient_claims"`),
   * typically a Conditional Access authentication context required for a step-up action.
   * Redirects away from the page, so it does not return.
   *
   * @param claimsBase64 the `claims` parameter from the challenge, still base64-encoded
   */
  abstract acquireTokenWithClaims(claimsBase64: string): void;

  abstract isAuthenticated(): boolean;

  abstract login(): void;

  /**
   * Submits built-in email/password credentials. Only ever called from `login.component.ts`'s
   * non-Entra branch, so `MsalTokenSource` rejects rather than no-ops — a caller that reaches it
   * anyway has a real bug to surface, not a mode to silently ignore.
   */
  abstract loginWithCredentials(email: string, password: string): Observable<void>;

  abstract logout(): void;

  // ── Local-only operations ───────────────────────────────────────────────────
  // Meaningful only when this app mints and stores its own tokens. The Entra implementation
  // rejects them rather than failing silently: impersonation and direct token assignment have
  // no equivalent when Entra owns the session, and the backend already refuses impersonation
  // when ENTRA_ENABLED=true.

  abstract setToken(token: string): void;

  abstract clearToken(): void;

  /**
   * Observable now (was synchronous): entering impersonation means exchanging the token for a
   * session cookie via `POST /api/v1/public/auth/impersonate` — a network round-trip — rather
   * than just writing to localStorage. Callers must subscribe before navigating.
   */
  abstract enterImpersonation(token: string, entityId: string, entityName: string): Observable<void>;

  /**
   * Observable now, for the same reason: restoring (or clearing) the session is a
   * `POST /api/v1/auth/exit-impersonation` call under `CookieTokenSource`.
   */
  abstract exitImpersonation(): Observable<void>;

  abstract getImpersonationMeta(): { entityId: string; entityName: string } | null;

  /** True when the app can hand out impersonation sessions at all. */
  abstract supportsImpersonation(): boolean;
}
