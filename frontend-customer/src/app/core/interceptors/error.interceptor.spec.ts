import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { errorInterceptor } from './error.interceptor';
import { AuthService } from '../auth/auth.service';
import { clearAuthRedirectCooldown } from '../auth/redirect-guard';

describe('errorInterceptor 401 handling', () => {
    let http: HttpClient;
    let httpMock: HttpTestingController;
    let snackBar: { open: ReturnType<typeof vi.fn> };
    let auth: { clearToken: ReturnType<typeof vi.fn>; login: ReturnType<typeof vi.fn>; acquireTokenWithClaims: ReturnType<typeof vi.fn> };

    beforeEach(() => {
        clearAuthRedirectCooldown();
        snackBar = { open: vi.fn() };
        auth = { clearToken: vi.fn(), login: vi.fn(), acquireTokenWithClaims: vi.fn() };
        TestBed.configureTestingModule({
            providers: [
                provideZonelessChangeDetection(),
                provideHttpClient(withInterceptors([errorInterceptor])),
                provideHttpClientTesting(),
                { provide: MatSnackBar, useValue: snackBar },
                { provide: AuthService, useValue: auth },
            ],
        });
        http = TestBed.inject(HttpClient);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
        httpMock.verify();
        clearAuthRedirectCooldown();
    });

    it.each(['/api/v1/public/auth/login', '/api/v1/public/auth/impersonate', '/api/v1/auth/session'])(
        'a 401 on %s reaches the caller without a toast or redirect and leaves the guard unarmed', (url) => {
            let status = 0;
            http.post(url, {}).subscribe({ error: e => (status = e.status) });
            httpMock.expectOne(url).flush({}, { status: 401, statusText: 'Unauthorized' });
            expect(status).toBe(401);
            expect(snackBar.open).not.toHaveBeenCalled();
            expect(auth.login).not.toHaveBeenCalled();

            // The guard was not consumed, so a real expiry still redirects.
            http.get('/api/v1/assets').subscribe({ error: () => undefined });
            httpMock.expectOne('/api/v1/assets').flush({}, { status: 401, statusText: 'Unauthorized' });
            expect(auth.clearToken).toHaveBeenCalledTimes(1);
            expect(auth.login).toHaveBeenCalledTimes(1);
        });

    it('a genuine expired-session 401 redirects exactly once even with parallel failures', () => {
        http.get('/api/v1/a').subscribe({ error: () => undefined });
        http.get('/api/v1/b').subscribe({ error: () => undefined });
        httpMock.expectOne('/api/v1/a').flush({}, { status: 401, statusText: 'Unauthorized' });
        httpMock.expectOne('/api/v1/b').flush({}, { status: 401, statusText: 'Unauthorized' });
        expect(auth.login).toHaveBeenCalledTimes(1);
    });
});
