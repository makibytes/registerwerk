import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { AUTH_CONFIG } from './auth-config';
import { TokenSource } from './token-source';

/**
 * The app's view of "who is signed in".
 *
 * Its public surface is unchanged from when it owned localStorage directly — every guard,
 * component and interceptor still calls the same methods. What changed is that token custody
 * now lives behind {@link TokenSource}, so the same code works whether the token was minted by
 * our own backend or acquired from Microsoft Entra.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly tokens = inject(TokenSource);
  private readonly config = inject(AUTH_CONFIG);

  // ── Token storage ──────────────────────────────────────────────────────────

  getToken(): string | null {
    return this.tokens.getToken();
  }

  setToken(token: string): void {
    this.tokens.setToken(token);
  }

  clearToken(): void {
    this.tokens.clearToken();
  }

  /** A token fit to send, refreshing it if the source supports that. Used by the interceptor. */
  acquireToken$(): Observable<string | null> {
    return this.tokens.acquireToken$();
  }

  /** Re-authenticate to satisfy an OAuth2 claims challenge. Redirects; does not return. */
  acquireTokenWithClaims(claimsBase64: string): void {
    this.tokens.acquireTokenWithClaims(claimsBase64);
  }

  // ── Authentication state ───────────────────────────────────────────────────

  isAuthenticated(): Observable<boolean> {
    return of(this.tokens.isAuthenticated());
  }

  isAuthenticatedSync(): boolean {
    return this.tokens.isAuthenticated();
  }

  // ── JWT claims ─────────────────────────────────────────────────────────────

  getUserRoles(): string[] {
    const payload = this.payload();
    if (!payload) return [];
    const roles =
      payload['roles'] ?? (payload['realm_access'] as Record<string, unknown>)?.['roles'] ?? [];
    return Array.isArray(roles) ? roles : [];
  }

  getEntityId(): string | null {
    const payload = this.payload();
    if (!payload) return null;
    return (payload['entityId'] as string) ?? (payload['entity_id'] as string) ?? null;
  }

  getUserEmail(): string | null {
    const payload = this.payload();
    if (!payload) return null;
    return (
      (payload['email'] as string) ??
      (payload['preferred_username'] as string) ??
      (payload['upn'] as string) ??
      null
    );
  }

  getUserName(): string | null {
    const payload = this.payload();
    if (!payload) return null;
    return (payload['name'] as string) ?? null;
  }

  hasRole(role: string): boolean {
    return this.getUserRoles().includes(role);
  }

  // ── Impersonation ──────────────────────────────────────────────────────────
  // Only available in local auth mode. The backend refuses to mint an impersonation token when
  // ENTRA_ENABLED=true, because there is no way to represent "admin acting as customer" in a
  // token Entra issued — so the UI must not offer the flow either.

  isImpersonating(): boolean {
    const payload = this.payload();
    return payload ? payload['impersonating'] === true : false;
  }

  supportsImpersonation(): boolean {
    return this.tokens.supportsImpersonation();
  }

  getImpersonationMeta(): { entityId: string; entityName: string } | null {
    return this.tokens.getImpersonationMeta();
  }

  enterImpersonation(impersonationToken: string, entityId: string, entityName: string): Observable<void> {
    return this.tokens.enterImpersonation(impersonationToken, entityId, entityName);
  }

  exitImpersonation(): Observable<void> {
    return this.tokens.exitImpersonation();
  }

  // ── Sign-in mode ───────────────────────────────────────────────────────────

  isEntraMode(): boolean {
    return this.config.mode === 'ENTRA';
  }

  /** Whether invite and password-reset links should be offered — false under Entra sign-in. */
  isLocalRegistrationEnabled(): boolean {
    return this.config.localRegistrationEnabled;
  }

  // ── Navigation helpers ─────────────────────────────────────────────────────

  login(): void {
    this.tokens.login();
  }

  loginWithCredentials(email: string, password: string): Observable<void> {
    return this.tokens.loginWithCredentials(email, password);
  }

  logout(): void {
    this.tokens.logout();
  }

  // ── Private helpers ────────────────────────────────────────────────────────

  private payload(): Record<string, unknown> | null {
    return this.tokens.getProfile();
  }
}
