import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { Observable, of } from 'rxjs';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  const dummyTree = {} as UrlTree;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated']);
    router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.returnValue(dummyTree);

    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authService },
        { provide: Router, useValue: router },
      ],
    });
  });

  function runGuard(): Observable<boolean | UrlTree> {
    return TestBed.runInInjectionContext(
      () => authGuard({} as never, { url: '/dashboard' } as never) as Observable<boolean | UrlTree>
    );
  }

  it('allows navigation when the user is authenticated', done => {
    authService.isAuthenticated.and.returnValue(of(true));

    runGuard().subscribe(value => {
      expect(value).toBe(true);
      expect(router.createUrlTree).not.toHaveBeenCalled();
      done();
    });
  });

  it('redirects to /login when the user is not authenticated', done => {
    authService.isAuthenticated.and.returnValue(of(false));

    runGuard().subscribe(value => {
      expect(router.createUrlTree).toHaveBeenCalledWith(['/login']);
      expect(value).toBe(dummyTree);
      done();
    });
  });
});
