import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, of } from 'rxjs';

/**
 * AuthService — wraps token storage / JWT inspection.
 *
 * In production this would be backed by MSAL or a custom OIDC flow.
 * The service is intentionally thin so it can be swapped without touching
 * components.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY = 'registerwerk_customer_token';
  private readonly router = inject(Router);

  // ── Token storage ──────────────────────────────────────────────────────────

  getToken(): string | null {
    return localStorage.getItem(this.TOKEN_KEY);
  }

  setToken(token: string): void {
    localStorage.setItem(this.TOKEN_KEY, token);
  }

  clearToken(): void {
    localStorage.removeItem(this.TOKEN_KEY);
  }

  // ── Authentication state ───────────────────────────────────────────────────

  isAuthenticated(): Observable<boolean> {
    const token = this.getToken();
    if (!token) return of(false);
    try {
      const payload = this.decodePayload(token);
      const now = Math.floor(Date.now() / 1000);
      return of((payload['exp'] as number) > now);
    } catch {
      return of(false);
    }
  }

  isAuthenticatedSync(): boolean {
    const token = this.getToken();
    if (!token) return false;
    try {
      const payload = this.decodePayload(token);
      const now = Math.floor(Date.now() / 1000);
      return (payload['exp'] as number) > now;
    } catch {
      return false;
    }
  }

  // ── JWT claims ─────────────────────────────────────────────────────────────

  getUserRoles(): string[] {
    const token = this.getToken();
    if (!token) return [];
    try {
      const payload = this.decodePayload(token);
      const roles = payload['roles'] ?? (payload['realm_access'] as Record<string, unknown>)?.['roles'] ?? [];
      return Array.isArray(roles) ? roles : [];
    } catch {
      return [];
    }
  }

  getEntityId(): string | null {
    const token = this.getToken();
    if (!token) return null;
    try {
      const payload = this.decodePayload(token);
      return (payload['entityId'] as string) ?? null;
    } catch {
      return null;
    }
  }

  getUserEmail(): string | null {
    const token = this.getToken();
    if (!token) return null;
    try {
      const payload = this.decodePayload(token);
      return (payload['email'] as string) ?? (payload['upn'] as string) ?? null;
    } catch {
      return null;
    }
  }

  getUserName(): string | null {
    const token = this.getToken();
    if (!token) return null;
    try {
      const payload = this.decodePayload(token);
      return (payload['name'] as string) ?? null;
    } catch {
      return null;
    }
  }

  hasRole(role: string): boolean {
    return this.getUserRoles().includes(role);
  }

  // ── Navigation helpers ─────────────────────────────────────────────────────

  login(): void {
    this.router.navigate(['/login']);
  }

  logout(): void {
    this.clearToken();
    this.router.navigate(['/login']);
  }

  // ── Private helpers ────────────────────────────────────────────────────────

  private decodePayload(token: string): Record<string, unknown> {
    const base64 = token.split('.')[1];
    if (!base64) throw new Error('Invalid token');
    const json = atob(base64.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(json) as Record<string, unknown>;
  }
}
