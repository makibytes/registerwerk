import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { Observable, of } from 'rxjs';
import { authGuard } from './auth.guard';
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

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['ensureInitialized']);

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
});
