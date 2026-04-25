import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable } from 'rxjs';

const TOKEN_KEY = 'registerwerk_operator_token';
const ENTITY_ID_KEY = 'registerwerk_operator_entity_id';
const TOKEN_TTL_SECONDS = 8 * 60 * 60;

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _isAuthenticated$ = new BehaviorSubject<boolean>(
    this._hasValidToken()
  );

  constructor(private readonly router: Router) {}

  isAuthenticated(): Observable<boolean> {
    return this._isAuthenticated$.asObservable();
  }

  isAuthenticatedSnapshot(): boolean {
    return this._isAuthenticated$.getValue();
  }

  login(): void {
    // In a real implementation, this would initiate the MSAL OAuth2 flow.
    // For now, we simulate a successful login by writing a stub token.
    const issuedAt = Math.floor(Date.now() / 1000);
    const stubToken = btoa(JSON.stringify({
      sub: 'operator-1',
      entityId: 'operator-1',
      roles: ['REGISTRY_ADMIN'],
      iat: issuedAt,
      exp: issuedAt + TOKEN_TTL_SECONDS,
    }));
    localStorage.setItem(TOKEN_KEY, stubToken);
    localStorage.setItem(ENTITY_ID_KEY, 'operator-1');
    this._isAuthenticated$.next(true);
    this.router.navigate(['/dashboard']);
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ENTITY_ID_KEY);
    this._isAuthenticated$.next(false);
    this.router.navigate(['/login']);
  }

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  getCurrentEntityId(): string | null {
    return localStorage.getItem(ENTITY_ID_KEY);
  }

  private _hasValidToken(): boolean {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      return false;
    }
    try {
      const payload = JSON.parse(atob(token)) as { exp?: number };
      const now = Math.floor(Date.now() / 1000);
      return typeof payload.exp === 'number' && payload.exp > now;
    } catch {
      return false;
    }
  }
}
