import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, of } from 'rxjs';
import { TokenSource } from './token-source';

const TOKEN_KEY = 'registerwerk_customer_token';
const ADMIN_TOKEN_KEY = 'registerwerk_customer_admin_token';
const IMP_META_KEY = 'registerwerk_customer_impersonation_meta';

/**
 * The built-in login path: the backend mints an HS256 token, we keep it in localStorage, and
 * there is nothing to refresh — when it expires the user signs in again.
 *
 * This is the behaviour the app has always had; it moved here unchanged so that `AuthService`
 * could delegate to either this or `MsalTokenSource` without any caller noticing.
 */
@Injectable()
export class LocalStorageTokenSource extends TokenSource {
  private readonly router = inject(Router);

  async initialize(): Promise<void> {
    // Nothing to do — the token is already in localStorage or it isn't.
  }

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  acquireToken$(): Observable<string | null> {
    return of(this.getToken());
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
    const token = this.getToken();
    if (!token) return false;
    try {
      const payload = decodePayload(token);
      return (payload['exp'] as number) > Math.floor(Date.now() / 1000);
    } catch {
      return false;
    }
  }

  setToken(token: string): void {
    localStorage.setItem(TOKEN_KEY, token);
  }

  clearToken(): void {
    localStorage.removeItem(TOKEN_KEY);
  }

  login(): void {
    this.router.navigate(['/login']);
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ADMIN_TOKEN_KEY);
    localStorage.removeItem(IMP_META_KEY);
    this.router.navigate(['/login']);
  }

  enterImpersonation(token: string, entityId: string, entityName: string): void {
    const current = this.getToken();
    if (current) {
      localStorage.setItem(ADMIN_TOKEN_KEY, current);
    }
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(IMP_META_KEY, JSON.stringify({ entityId, entityName }));
  }

  exitImpersonation(): void {
    const adminToken = localStorage.getItem(ADMIN_TOKEN_KEY);
    localStorage.removeItem(IMP_META_KEY);
    localStorage.removeItem(ADMIN_TOKEN_KEY);
    if (adminToken) {
      localStorage.setItem(TOKEN_KEY, adminToken);
    } else {
      localStorage.removeItem(TOKEN_KEY);
    }
  }

  getImpersonationMeta(): { entityId: string; entityName: string } | null {
    const raw = localStorage.getItem(IMP_META_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as { entityId: string; entityName: string };
    } catch {
      return null;
    }
  }

  supportsImpersonation(): boolean {
    return true;
  }
}

export function decodePayload(token: string): Record<string, unknown> {
  const base64 = token.split('.')[1];
  if (!base64) throw new Error('Invalid token');
  const json = atob(base64.replace(/-/g, '+').replace(/_/g, '/'));
  return JSON.parse(json) as Record<string, unknown>;
}
