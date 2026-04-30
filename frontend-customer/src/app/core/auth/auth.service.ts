import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, of } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY = 'registerwerk_customer_token';
  private readonly ADMIN_TOKEN_KEY = 'registerwerk_customer_admin_token';
  private readonly IMP_META_KEY = 'registerwerk_customer_impersonation_meta';
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
      return (payload['entityId'] as string) ?? (payload['entity_id'] as string) ?? null;
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

  // ── Impersonation ──────────────────────────────────────────────────────────

  isImpersonating(): boolean {
    const token = this.getToken();
    if (!token) return false;
    try {
      return this.decodePayload(token)['imp'] === true;
    } catch {
      return false;
    }
  }

  getImpersonationMeta(): { entityId: string; entityName: string } | null {
    const raw = localStorage.getItem(this.IMP_META_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as { entityId: string; entityName: string };
    } catch {
      return null;
    }
  }

  enterImpersonation(impersonationToken: string, entityId: string, entityName: string): void {
    const currentToken = this.getToken();
    if (currentToken) {
      localStorage.setItem(this.ADMIN_TOKEN_KEY, currentToken);
    }
    localStorage.setItem(this.TOKEN_KEY, impersonationToken);
    localStorage.setItem(this.IMP_META_KEY, JSON.stringify({ entityId, entityName }));
  }

  exitImpersonation(): void {
    const adminToken = localStorage.getItem(this.ADMIN_TOKEN_KEY);
    localStorage.removeItem(this.IMP_META_KEY);
    localStorage.removeItem(this.ADMIN_TOKEN_KEY);
    if (adminToken) {
      localStorage.setItem(this.TOKEN_KEY, adminToken);
    } else {
      localStorage.removeItem(this.TOKEN_KEY);
    }
  }

  // ── Navigation helpers ─────────────────────────────────────────────────────

  login(): void {
    this.router.navigate(['/login']);
  }

  logout(): void {
    localStorage.removeItem(this.TOKEN_KEY);
    localStorage.removeItem(this.ADMIN_TOKEN_KEY);
    localStorage.removeItem(this.IMP_META_KEY);
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
