import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { TokenSource } from '../auth/token-source';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let tokenSource: jasmine.SpyObj<TokenSource>;

  beforeEach(() => {
    tokenSource = jasmine.createSpyObj<TokenSource>('TokenSource', ['acquireToken$']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: TokenSource, useValue: tokenSource },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('clones the request with withCredentials: true so the httpOnly session cookie attaches', () => {
    tokenSource.acquireToken$.and.returnValue(of(null));

    http.get('/api/v1/investments').subscribe();
    const req = httpMock.expectOne('/api/v1/investments');

    expect(req.request.withCredentials).toBe(true);
    req.flush({});
  });

  it('attaches an Authorization header from the TokenSource when one is provided (Entra mode)', () => {
    tokenSource.acquireToken$.and.returnValue(of('acquired-token'));

    http.get('/api/v1/investments').subscribe();
    const req = httpMock.expectOne('/api/v1/investments');

    expect(req.request.headers.get('Authorization')).toBe('Bearer acquired-token');
    expect(req.request.withCredentials).toBe(true);
    req.flush({});
  });

  it('does not set an Authorization header when the TokenSource has no token (cookie auth)', () => {
    tokenSource.acquireToken$.and.returnValue(of(null));

    http.get('/api/v1/investments').subscribe();
    const req = httpMock.expectOne('/api/v1/investments');

    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('leaves an explicit Authorization header alone (step-up requests carry their own short-lived token)', () => {
    http.get('/api/v1/step-up/confirm', {
      headers: { Authorization: 'Bearer step-up-token' },
    }).subscribe();

    const req = httpMock.expectOne('/api/v1/step-up/confirm');

    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-token');
    expect(req.request.withCredentials).toBe(true);
    // The interceptor must short-circuit before ever asking the TokenSource for a token.
    expect(tokenSource.acquireToken$).not.toHaveBeenCalled();
    req.flush({});
  });
});
