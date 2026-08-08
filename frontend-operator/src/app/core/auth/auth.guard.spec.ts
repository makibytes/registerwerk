import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { Observable, of } from 'rxjs';
import { authGuard, roleGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let router: Router;
  let urlTree: UrlTree;

  function runGuard(): Observable<boolean | UrlTree> {
    return TestBed.runInInjectionContext(
      () => authGuard({} as never, {} as never) as Observable<boolean | UrlTree>
    );
  }

  function runRoleGuard(roles: string[]): boolean | UrlTree {
    return TestBed.runInInjectionContext(
      () => roleGuard({ data: { roles } } as never, {} as never) as boolean | UrlTree
    );
  }

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['ensureInitialized', 'hasRole']);

    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authServiceSpy }],
    });

    router = TestBed.inject(Router);
    urlTree = router.createUrlTree(['/login']);
    spyOn(router, 'createUrlTree').and.returnValue(urlTree);
  });

  it('allows navigation when the session is authenticated', done => {
    authServiceSpy.ensureInitialized.and.returnValue(of(true));

    runGuard().subscribe(value => {
      expect(value).toBeTrue();
      done();
    });
  });

  it('redirects to /login when the session is not authenticated', done => {
    authServiceSpy.ensureInitialized.and.returnValue(of(false));

    runGuard().subscribe(value => {
      expect(value).toBe(urlTree);
      expect(router.createUrlTree).toHaveBeenCalledWith(['/login']);
      done();
    });
  });

  it('allows a route when the user has any required role', () => {
    authServiceSpy.hasRole.and.callFake((role) => role === 'AUDIT');

    expect(runRoleGuard(['REGISTRY_ADMIN', 'AUDIT'])).toBeTrue();
  });

  it('redirects unauthorized roles to the role-aware dashboard', () => {
    authServiceSpy.hasRole.and.returnValue(false);
    const dashboardTree = router.createUrlTree(['/dashboard']);
    (router.createUrlTree as jasmine.Spy).and.returnValue(dashboardTree);

    expect(runRoleGuard(['REGISTRY_ADMIN'])).toBe(dashboardTree);
    expect(router.createUrlTree).toHaveBeenCalledWith(['/dashboard']);
  });
});
