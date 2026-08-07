import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { errorInterceptor } from './error.interceptor';
import { AuthService } from '../auth/auth.service';

describe('errorInterceptor', () => {
  let httpMock: HttpTestingController;
  let http: HttpClient;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let router: Router;

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['logout']);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });

    httpMock = TestBed.inject(HttpTestingController);
    http = TestBed.inject(HttpClient);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    spyOn(console, 'error');
  });

  afterEach(() => httpMock.verify());

  it('logs out and redirects to /login on a 401', () => {
    let errored = false;
    http.get('/api/v1/protected').subscribe({ error: () => (errored = true) });

    httpMock.expectOne('/api/v1/protected').flush('nope', { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBeTrue();
    expect(authServiceSpy.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('does not log out on a 403, but still propagates the error', () => {
    let errored = false;
    http.get('/api/v1/forbidden').subscribe({ error: () => (errored = true) });

    httpMock.expectOne('/api/v1/forbidden').flush('nope', { status: 403, statusText: 'Forbidden' });

    expect(errored).toBeTrue();
    expect(authServiceSpy.logout).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('propagates network errors (status 0) without logging out', () => {
    let errored = false;
    http.get('/api/v1/unreachable').subscribe({ error: () => (errored = true) });

    httpMock.expectOne('/api/v1/unreachable').error(new ProgressEvent('error'), { status: 0 });

    expect(errored).toBeTrue();
    expect(authServiceSpy.logout).not.toHaveBeenCalled();
  });

  it('passes through successful responses untouched', () => {
    let body: unknown;
    http.get('/api/v1/ok').subscribe(res => (body = res));

    httpMock.expectOne('/api/v1/ok').flush({ ok: true });

    expect(body).toEqual({ ok: true });
    expect(authServiceSpy.logout).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
