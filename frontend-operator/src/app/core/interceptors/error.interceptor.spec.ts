import { afterEach, beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { SUPPRESS_ERROR_TOAST } from '@registerwerk/ui';
import { errorInterceptor } from './error.interceptor';
import { AuthService } from '../auth/auth.service';
import { MatSnackBar } from '@angular/material/snack-bar';

describe('errorInterceptor', () => {
    let httpMock: HttpTestingController;
    let http: HttpClient;
    let authServiceSpy: MockedObject<Pick<AuthService, 'clearSession' | 'markPasswordChangeRequired'>>;
    let snackBarSpy: MockedObject<Pick<MatSnackBar, 'open'>>;
    let router: Router;

    beforeEach(() => {
        authServiceSpy = {
            clearSession: vi.fn().mockName("AuthService.clearSession"),
            markPasswordChangeRequired: vi.fn().mockName("AuthService.markPasswordChangeRequired"),
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

    it('treats 403 PASSWORD_CHANGE_REQUIRED as a routing signal, not an access-denied failure', () => {
        let errored = false;
        http.get('/api/v1/assets').subscribe({ error: () => (errored = true) });

        httpMock.expectOne('/api/v1/assets').flush(
            { status: 403, code: 'PASSWORD_CHANGE_REQUIRED', message: 'The password must be changed' },
            { status: 403, statusText: 'Forbidden' });

        expect(errored).toBe(true);
        expect(authServiceSpy.markPasswordChangeRequired).toHaveBeenCalled();
        expect(router.navigate).toHaveBeenCalledWith(['/change-password']);
        expect(snackBarSpy.open).not.toHaveBeenCalled();
        expect(authServiceSpy.clearSession).not.toHaveBeenCalled();
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

    it('leaves a 403 to the caller when the request carries SUPPRESS_ERROR_TOAST (no generic "Access denied" over an inline message)', () => {
        let error: { error?: { code?: string } } | undefined;
        const context = new HttpContext().set(SUPPRESS_ERROR_TOAST, true);
        http.post('/api/v1/auth/step-up', {}, { context }).subscribe({ error: e => (error = e) });

        httpMock.expectOne('/api/v1/auth/step-up').flush(
            { status: 403, code: 'STEP_UP_CODE_INVALID', message: 'The code is not valid' },
            { status: 403, statusText: 'Forbidden' });

        expect(error?.error?.code).toBe('STEP_UP_CODE_INVALID');
        expect(snackBarSpy.open).not.toHaveBeenCalled();
    });

    it('still routes a forced password change and a 401 for a request that suppresses the toast', () => {
        const context = new HttpContext().set(SUPPRESS_ERROR_TOAST, true);
        http.get('/api/v1/a', { context }).subscribe({ error: () => undefined });
        httpMock.expectOne('/api/v1/a').flush(
            { code: 'PASSWORD_CHANGE_REQUIRED' }, { status: 403, statusText: 'Forbidden' });
        expect(authServiceSpy.markPasswordChangeRequired).toHaveBeenCalled();

        http.get('/api/v1/b', { context }).subscribe({ error: () => undefined });
        httpMock.expectOne('/api/v1/b').flush('nope', { status: 401, statusText: 'Unauthorized' });
        expect(authServiceSpy.clearSession).toHaveBeenCalled();
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
