import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { Observable, of } from 'rxjs';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
    let authService: MockedObject<Pick<AuthService, 'isAuthenticated'>>;
    let router: MockedObject<Pick<Router, 'createUrlTree'>>;
    const dummyTree = {} as UrlTree;

    beforeEach(() => {
        authService = {
            isAuthenticated: vi.fn().mockName("AuthService.isAuthenticated")
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

    it('redirects to /login when the user is not authenticated', async () => {
        authService.isAuthenticated.mockReturnValue(of(false));

        runGuard().subscribe(value => {
            expect(router.createUrlTree).toHaveBeenCalledWith(['/login']);
            expect(value).toBe(dummyTree);
            ;
        });
    });
});
