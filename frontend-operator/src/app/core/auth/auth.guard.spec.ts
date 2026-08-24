import { beforeEach, describe, expect, it, type Mock, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { Observable, of } from 'rxjs';
import { authGuard, roleGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
    let authServiceSpy: MockedObject<Pick<AuthService, 'ensureInitialized' | 'hasRole'>>;
    let router: Router;
    let urlTree: UrlTree;

    function runGuard(): Observable<boolean | UrlTree> {
        return TestBed.runInInjectionContext(() => authGuard({} as never, {} as never) as Observable<boolean | UrlTree>);
    }

    function runRoleGuard(roles: string[]): boolean | UrlTree {
        return TestBed.runInInjectionContext(() => roleGuard({ data: { roles } } as never, {} as never) as boolean | UrlTree);
    }

    beforeEach(() => {
        authServiceSpy = {
            ensureInitialized: vi.fn().mockName("AuthService.ensureInitialized"),
            hasRole: vi.fn().mockName("AuthService.hasRole")
        };

        TestBed.configureTestingModule({
            providers: [{ provide: AuthService, useValue: authServiceSpy }],
        });

        router = TestBed.inject(Router);
        urlTree = router.createUrlTree(['/login']);
        vi.spyOn(router, 'createUrlTree').mockReturnValue(urlTree);
    });

    it('allows navigation when the session is authenticated', async () => {
        authServiceSpy.ensureInitialized.mockReturnValue(of(true));

        runGuard().subscribe(value => {
            expect(value).toBe(true);
            ;
        });
    });

    it('redirects to /login when the session is not authenticated', async () => {
        authServiceSpy.ensureInitialized.mockReturnValue(of(false));

        runGuard().subscribe(value => {
            expect(value).toBe(urlTree);
            expect(router.createUrlTree).toHaveBeenCalledWith(['/login']);
            ;
        });
    });

    it('allows a route when the user has any required role', () => {
        authServiceSpy.hasRole.mockImplementation((role) => role === 'AUDIT');

        expect(runRoleGuard(['REGISTRY_ADMIN', 'AUDIT'])).toBe(true);
    });

    it('redirects unauthorized roles to the role-aware dashboard', () => {
        authServiceSpy.hasRole.mockReturnValue(false);
        const dashboardTree = router.createUrlTree(['/dashboard']);
        (router.createUrlTree as Mock).mockReturnValue(dashboardTree);

        expect(runRoleGuard(['REGISTRY_ADMIN'])).toBe(dashboardTree);
        expect(router.createUrlTree).toHaveBeenCalledWith(['/dashboard']);
    });
});
