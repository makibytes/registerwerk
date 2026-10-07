import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { Observable, firstValueFrom, of } from 'rxjs';
import { authGuard, changePasswordGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
    let authService: MockedObject<Pick<AuthService, 'isAuthenticated' | 'isPasswordChangeRequired'>>;
    let router: MockedObject<Pick<Router, 'createUrlTree'>>;
    const dummyTree = {} as UrlTree;

    beforeEach(() => {
        authService = {
            isAuthenticated: vi.fn().mockName("AuthService.isAuthenticated"),
            isPasswordChangeRequired: vi.fn().mockName("AuthService.isPasswordChangeRequired").mockReturnValue(false),
        };
        router = {
            createUrlTree: vi.fn().mockName("Router.createUrlTree")
        };
        router.createUrlTree.mockReturnValue(dummyTree);

        TestBed.configureTestingModule({
            providers: [
                { provide: AuthService, useValue: authService },
                { provide: Router, useValue: router },
            ],
        });
    });

    function runGuard(): Observable<boolean | UrlTree> {
        return TestBed.runInInjectionContext(() => authGuard({} as never, { url: '/dashboard' } as never) as Observable<boolean | UrlTree>);
    }

    it('allows navigation when the user is authenticated', async () => {
        authService.isAuthenticated.mockReturnValue(of(true));

        runGuard().subscribe(value => {
            expect(value).toBe(true);
            expect(router.createUrlTree).not.toHaveBeenCalled();
            ;
        });
    });

    it('sends a must_change_password session to /change-password', async () => {
        authService.isAuthenticated.mockReturnValue(of(true));
        authService.isPasswordChangeRequired.mockReturnValue(true);

        const value = await firstValueFrom(runGuard());

        expect(router.createUrlTree).toHaveBeenCalledWith(['/change-password']);
        expect(value).toBe(dummyTree);
    });

    it('changePasswordGuard lets a restricted session in but not an anonymous one', async () => {
        const run = () => TestBed.runInInjectionContext(
            () => changePasswordGuard({} as never, {} as never) as Observable<boolean | UrlTree>);
        authService.isAuthenticated.mockReturnValue(of(true));
        authService.isPasswordChangeRequired.mockReturnValue(true);
        expect(await firstValueFrom(run())).toBe(true);

        authService.isAuthenticated.mockReturnValue(of(false));
        expect(await firstValueFrom(run())).toBe(dummyTree);
        expect(router.createUrlTree).toHaveBeenCalledWith(['/login']);
    });

    it('redirects to /login when the user is not authenticated', async () => {
        authService.isAuthenticated.mockReturnValue(of(false));

        runGuard().subscribe(value => {
            expect(router.createUrlTree).toHaveBeenCalledWith(['/login']);
            expect(value).toBe(dummyTree);
            ;
        });
    });
});
