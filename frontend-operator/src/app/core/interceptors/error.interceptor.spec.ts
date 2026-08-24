import { afterEach, beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { errorInterceptor } from './error.interceptor';
import { AuthService } from '../auth/auth.service';
import { MatSnackBar } from '@angular/material/snack-bar';

describe('errorInterceptor', () => {
    let httpMock: HttpTestingController;
    let http: HttpClient;
    let authServiceSpy: MockedObject<Pick<AuthService, 'clearSession'>>;
    let snackBarSpy: MockedObject<Pick<MatSnackBar, 'open'>>;
    let router: Router;

    beforeEach(() => {
        authServiceSpy = {
            clearSession: vi.fn().mockName("AuthService.clearSession")
        };
        snackBarSpy = {
            open: vi.fn().mockName("MatSnackBar.open")
        };

        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(withInterceptors([errorInterceptor])),
                provideHttpClientTesting(),
                { provide: AuthService, useValue: authServiceSpy },
                { provide: MatSnackBar, useValue: snackBarSpy },
            ],
        });

        httpMock = TestBed.inject(HttpTestingController);
        http = TestBed.inject(HttpClient);
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate').mockResolvedValue(true);
        vi.spyOn(console, 'error').mockReturnValue(undefined);
    });

    afterEach(() => httpMock.verify());

    it('clears local session state and redirects to /login on a 401 without an HTTP logout loop', () => {
        let errored = false;
        http.get('/api/v1/protected').subscribe({ error: () => (errored = true) });

        httpMock.expectOne('/api/v1/protected').flush('nope', { status: 401, statusText: 'Unauthorized' });

        expect(errored).toBe(true);
        expect(authServiceSpy.clearSession).toHaveBeenCalled();
        expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });

    it('leaves an invalid-login response on the login page for the form to handle', () => {
        http.post('/api/v1/public/auth/login', {}).subscribe({ error: () => undefined });

        httpMock.expectOne('/api/v1/public/auth/login')
            .flush('invalid', { status: 401, statusText: 'Unauthorized' });

        expect(authServiceSpy.clearSession).toHaveBeenCalled();
        expect(router.navigate).not.toHaveBeenCalled();
    });

    it('does not log out on a 403, but still propagates the error', () => {
        let errored = false;
        http.get('/api/v1/forbidden').subscribe({ error: () => (errored = true) });

        httpMock.expectOne('/api/v1/forbidden').flush('nope', { status: 403, statusText: 'Forbidden' });

        expect(errored).toBe(true);
        expect(authServiceSpy.clearSession).not.toHaveBeenCalled();
        expect(router.navigate).not.toHaveBeenCalled();
        expect(snackBarSpy.open).toHaveBeenCalled();
    });

    it('propagates network errors (status 0) without logging out', () => {
        let errored = false;
        http.get('/api/v1/unreachable').subscribe({ error: () => (errored = true) });

        httpMock.expectOne('/api/v1/unreachable').error(new ProgressEvent('error'), { status: 0 });

        expect(errored).toBe(true);
        expect(authServiceSpy.clearSession).not.toHaveBeenCalled();
        expect(snackBarSpy.open).toHaveBeenCalled();
    });

    it('passes through successful responses untouched', () => {
        let body: unknown;
        http.get('/api/v1/ok').subscribe(res => (body = res));

        httpMock.expectOne('/api/v1/ok').flush({ ok: true });

        expect(body).toEqual({ ok: true });
        expect(authServiceSpy.clearSession).not.toHaveBeenCalled();
        expect(router.navigate).not.toHaveBeenCalled();
    });
});
