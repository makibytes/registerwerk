import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable } from 'rxjs';

const TOKEN_KEY = 'registerwerk_operator_token';
const ENTITY_ID_KEY = 'registerwerk_operator_entity_id';

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
    const stubToken = btoa(JSON.stringify({ sub: 'operator-1', role: 'OPERATOR', iat: Date.now() }));
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
    return token !== null && token.length > 0;
  }
}
