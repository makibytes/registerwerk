import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';

describe('AuthService', () => {
    let service: AuthService;
    let httpMock: HttpTestingController;
    let router: Router;

    const profile = {
        userId: 'u-1',
        roles: ['REGISTRY_ADMIN'],
        email: 'admin@example.com',
        name: 'Admin User',
        entityId: null,
        expiresAt: Date.now() + 60000,
    };

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                AuthService,
                provideHttpClient(),
                provideHttpClientTesting(),
            ],
        });
        service = TestBed.inject(AuthService);
        httpMock = TestBed.inject(HttpTestingController);
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate').mockResolvedValue(true);
    });

    afterEach(() => {
        httpMock.verify();
    });

    it('starts unauthenticated with no profile', () => {
        expect(service.isAuthenticatedSnapshot()).toBe(false);
        expect(service.getUserRoles()).toEqual([]);
        expect(service.getUserEmail()).toBeNull();
        expect(service.getUserName()).toBeNull();
        expect(service.getUserId()).toBeNull();
    });

    describe('ensureInitialized()', () => {
        it('marks the user authenticated and stores the resolved profile on success', () => {
            let result: boolean | undefined;
            service.ensureInitialized().subscribe(v => (result = v));

            const req = httpMock.expectOne(`${environment.apiUrl}/auth/session`);
            expect(req.request.method).toBe('GET');
            req.flush(profile);

            expect(result).toBe(true);
            expect(service.isAuthenticatedSnapshot()).toBe(true);
            expect(service.getUserRoles()).toEqual(['REGISTRY_ADMIN']);
            expect(service.getUserEmail()).toBe('admin@example.com');
            expect(service.getUserName()).toBe('Admin User');
            expect(service.getUserId()).toBe('u-1');
            expect(service.hasRole('REGISTRY_ADMIN')).toBe(true);
            expect(service.hasRole('SOME_OTHER_ROLE')).toBe(false);
        });

        it('resolves to false and clears state when the session call fails (e.g. 401)', () => {
            let result: boolean | undefined;
            service.ensureInitialized().subscribe(v => (result = v));

            const req = httpMock.expectOne(`${environment.apiUrl}/auth/session`);
            req.flush('unauthorized', { status: 401, statusText: 'Unauthorized' });

            expect(result).toBe(false);
            expect(service.isAuthenticatedSnapshot()).toBe(false);
            expect(service.getUserRoles()).toEqual([]);
        });

        it('caches the in-flight/completed request — a second call does not issue a second HTTP request', () => {
            service.ensureInitialized().subscribe();
            const req = httpMock.expectOne(`${environment.apiUrl}/auth/session`);
            req.flush(profile);

            let secondResult: boolean | undefined;
            service.ensureInitialized().subscribe(v => (secondResult = v));

            httpMock.expectNone(`${environment.apiUrl}/auth/session`);
            expect(secondResult).toBe(true);
        });
    });

    describe('loginWithCredentials()', () => {
        it('POSTs credentials and marks the session authenticated on success', () => {
            let completed = false;
            service.loginWithCredentials('admin@example.com', 'hunter2').subscribe(() => (completed = true));

            const req = httpMock.expectOne(`${environment.apiUrl}/public/auth/login`);
            expect(req.request.method).toBe('POST');
            expect(req.request.body).toEqual({ email: 'admin@example.com', password: 'hunter2' });
            req.flush(profile);

            expect(completed).toBe(true);
            expect(service.isAuthenticatedSnapshot()).toBe(true);
            expect(service.getUserId()).toBe('u-1');
        });

        it('short-circuits a later ensureInitialized() call so it does not refetch the session', () => {
            service.loginWithCredentials('admin@example.com', 'hunter2').subscribe();
            httpMock.expectOne(`${environment.apiUrl}/public/auth/login`).flush(profile);

            let initResult: boolean | undefined;
            service.ensureInitialized().subscribe(v => (initResult = v));

            httpMock.expectNone(`${environment.apiUrl}/auth/session`);
            expect(initResult).toBe(true);
        });
    });

    describe('logout()', () => {
        it('POSTs to the logout endpoint, clears state, and redirects to /login', () => {
            service.loginWithCredentials('admin@example.com', 'hunter2').subscribe();
            httpMock.expectOne(`${environment.apiUrl}/public/auth/login`).flush(profile);
            expect(service.isAuthenticatedSnapshot()).toBe(true);

            service.logout();
            const req = httpMock.expectOne(`${environment.apiUrl}/public/auth/logout`);
            expect(req.request.method).toBe('POST');
            req.flush({});

            expect(service.isAuthenticatedSnapshot()).toBe(false);
            expect(service.getUserRoles()).toEqual([]);
            expect(router.navigate).toHaveBeenCalledWith(['/login']);
        });

        it('still drops client-side auth state and redirects when the logout call itself fails', () => {
            service.loginWithCredentials('admin@example.com', 'hunter2').subscribe();
            httpMock.expectOne(`${environment.apiUrl}/public/auth/login`).flush(profile);

            service.logout();
            const req = httpMock.expectOne(`${environment.apiUrl}/public/auth/logout`);
            req.flush('boom', { status: 500, statusText: 'Server Error' });

            expect(service.isAuthenticatedSnapshot()).toBe(false);
            expect(router.navigate).toHaveBeenCalledWith(['/login']);
        });

        it('re-fetches the session from the server after logout (cache was invalidated)', () => {
            service.loginWithCredentials('admin@example.com', 'hunter2').subscribe();
            httpMock.expectOne(`${environment.apiUrl}/public/auth/login`).flush(profile);

            service.logout();
            httpMock.expectOne(`${environment.apiUrl}/public/auth/logout`).flush({});

            let result: boolean | undefined;
            service.ensureInitialized().subscribe(v => (result = v));
            httpMock.expectOne(`${environment.apiUrl}/auth/session`).flush(profile);

            expect(result).toBe(true);
        });
    });

    describe('clearSession()', () => {
        it('clears state without making a logout request or navigating', () => {
            service.loginWithCredentials('admin@example.com', 'hunter2').subscribe();
            httpMock.expectOne(`${environment.apiUrl}/public/auth/login`).flush(profile);

            service.clearSession();

            expect(service.isAuthenticatedSnapshot()).toBe(false);
            expect(service.getUserRoles()).toEqual([]);
            httpMock.expectNone(`${environment.apiUrl}/public/auth/logout`);
            expect(router.navigate).not.toHaveBeenCalled();
        });
    });
});
