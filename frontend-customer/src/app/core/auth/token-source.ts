import { Observable } from 'rxjs';

/**
 * Where the app's bearer token comes from. Two implementations exist —
 * `LocalStorageTokenSource` for built-in password login and `MsalTokenSource` for Entra — and
 * `app.config.ts` picks one at bootstrap based on the fetched `AuthConfig`.
 *
 * `AuthService` delegates here, so guards, `getEntityId()`, the impersonation bar and every
 * feature component keep working unchanged regardless of which is active.
 */
export abstract class TokenSource {
  /** Runs once during app initialisation, before the router activates. */
  abstract initialize(): Promise<void>;

  /** Cached token, synchronously — for guards and claim inspection. Null when signed out. */
  abstract getToken(): string | null;

  /**
   * A token fit to send, acquiring or refreshing one if needed. The HTTP interceptor uses this
   * rather than `getToken()` so an Entra token can be silently renewed mid-session.
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

  abstract logout(): void;

  // ── Local-only operations ───────────────────────────────────────────────────
  // Meaningful only when this app mints and stores its own tokens. The Entra implementation
  // rejects them rather than failing silently: impersonation and direct token assignment have
  // no equivalent when Entra owns the session, and the backend already refuses impersonation
  // when ENTRA_ENABLED=true.

  abstract setToken(token: string): void;

  abstract clearToken(): void;

  abstract enterImpersonation(token: string, entityId: string, entityName: string): void;

  abstract exitImpersonation(): void;

  abstract getImpersonationMeta(): { entityId: string; entityName: string } | null;

  /** True when the app can hand out impersonation sessions at all. */
  abstract supportsImpersonation(): boolean;
}
