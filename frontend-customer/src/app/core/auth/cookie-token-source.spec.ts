import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { CookieTokenSource } from './cookie-token-source';
import { environment } from '../../../environments/environment';

describe('CookieTokenSource', () => {
  let source: CookieTokenSource;
  let httpMock: HttpTestingController;
  let router: jasmine.SpyObj<Router>;

  const sessionUrl = `${environment.apiUrl}/auth/session`;
  const loginUrl = `${environment.apiUrl}/public/auth/login`;
  const logoutUrl = `${environment.apiUrl}/public/auth/logout`;
  const impersonateUrl = `${environment.apiUrl}/public/auth/impersonate`;
  const exitImpersonationUrl = `${environment.apiUrl}/auth/exit-impersonation`;

  const profile = {
    userId: 'u1',
    roles: ['INVESTOR'],
    email: 'a@example.com',
    name: 'A B',
    entityId: 'ent-1',
    entityName: 'Acme',
    impersonating: false,
    expiresAt: 9999999999,
  };

  beforeEach(() => {
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    TestBed.configureTestingModule({
      providers: [
        CookieTokenSource,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
      ],
    });
    source = TestBed.inject(CookieTokenSource);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  describe('getToken()', () => {
    it('always returns null by design — the token lives only in the httpOnly cookie', () => {
      expect(source.getToken()).toBeNull();
    });
  });

  describe('initialize()', () => {
    it('fetches GET /auth/session and stores the returned profile', async () => {
      const initPromise = source.initialize();
      const req = httpMock.expectOne(sessionUrl);
      expect(req.request.method).toBe('GET');
      req.flush(profile);
      await initPromise;

      expect(source.isAuthenticated()).toBe(true);
      expect(source.getProfile()).toEqual(profile as unknown as Record<string, unknown>);
    });

    it('leaves the profile null when the session fetch fails (not signed in)', async () => {
      const initPromise = source.initialize();
      const req = httpMock.expectOne(sessionUrl);
      req.flush('unauthorized', { status: 401, statusText: 'Unauthorized' });
      await initPromise;

      expect(source.isAuthenticated()).toBe(false);
      expect(source.getProfile()).toBeNull();
    });
  });

  describe('loginWithCredentials()', () => {
    it('POSTs credentials and sets the profile from the response', async () => {
      const result = firstEmission(source.loginWithCredentials('a@example.com', 'secret'));
      const req = httpMock.expectOne(loginUrl);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ email: 'a@example.com', password: 'secret' });
      req.flush(profile);

      await result;
      expect(source.isAuthenticated()).toBe(true);
      expect(source.getProfile()!['entityId']).toBe('ent-1');
    });
  });

  describe('logout()', () => {
    it('POSTs to /public/auth/logout, clears the profile and navigates to /login', () => {
      // Prime a signed-in profile first.
      (source as unknown as { profile: unknown }).profile = profile;

      source.logout();
      const req = httpMock.expectOne(logoutUrl);
      expect(req.request.method).toBe('POST');
      req.flush({});

      expect(source.isAuthenticated()).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });

    it('still drops client-side state and navigates even if the logout call itself fails', () => {
      (source as unknown as { profile: unknown }).profile = profile;

      source.logout();
      const req = httpMock.expectOne(logoutUrl);
      req.flush('boom', { status: 500, statusText: 'Server Error' });

      expect(source.isAuthenticated()).toBe(false);
      expect(router.navigate).toHaveBeenCalledWith(['/login']);
    });
  });

  describe('acquireToken$() / acquireTokenWithClaims()', () => {
    it('acquireToken$() always resolves null — the cookie authenticates on its own', async () => {
      const value = await firstEmission(source.acquireToken$());
      expect(value).toBeNull();
    });

    it('acquireTokenWithClaims() is a no-op warning, not a crash, under local auth', () => {
      expect(() => source.acquireTokenWithClaims('base64')).not.toThrow();
    });
  });

  describe('setToken() / clearToken()', () => {
    it('setToken() is a documented no-op under cookie-based auth', () => {
      expect(() => source.setToken('irrelevant')).not.toThrow();
    });

    it('clearToken() drops the in-memory profile', () => {
      (source as unknown as { profile: unknown }).profile = profile;
      source.clearToken();
      expect(source.getProfile()).toBeNull();
    });
  });

  describe('enterImpersonation()', () => {
    it('POSTs the impersonation token and replaces the profile with the response', async () => {
      const impersonatedProfile = { ...profile, impersonating: true, entityId: 'ent-9' };
      const result = firstEmission(source.enterImpersonation('impersonation-tok', 'ent-9', 'Other Co'));
      const req = httpMock.expectOne(impersonateUrl);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ token: 'impersonation-tok' });
      req.flush(impersonatedProfile);

      await result;
      expect(source.getImpersonationMeta()).toEqual({ entityId: 'ent-9', entityName: 'Acme' });
    });
  });

  describe('exitImpersonation()', () => {
    it('POSTs exit-impersonation, then refetches /auth/session before completing (avoids a stale-profile race)', async () => {
      const restoredProfile = { ...profile, impersonating: false, entityId: 'admin-ent' };
      const resultPromise = firstEmission(source.exitImpersonation());

      // The POST must happen first...
      const postReq = httpMock.expectOne(exitImpersonationUrl);
      expect(postReq.request.method).toBe('POST');
      postReq.flush({});

      // ...and only after it resolves does the class refetch the session — proving the Observable
      // does not complete on the POST response alone.
      const getReq = httpMock.expectOne(sessionUrl);
      expect(getReq.request.method).toBe('GET');
      getReq.flush(restoredProfile);

      await resultPromise;
      expect(source.getProfile()!['entityId']).toBe('admin-ent');
    });

    it('clears the profile if the post-exit session refetch fails', async () => {
      (source as unknown as { profile: unknown }).profile = profile;
      const resultPromise = firstEmission(source.exitImpersonation());

      const postReq = httpMock.expectOne(exitImpersonationUrl);
      postReq.flush({});

      const getReq = httpMock.expectOne(sessionUrl);
      getReq.flush('gone', { status: 401, statusText: 'Unauthorized' });

      await resultPromise;
      expect(source.getProfile()).toBeNull();
    });
  });

  describe('getImpersonationMeta() / supportsImpersonation()', () => {
    it('returns null when not impersonating', () => {
      (source as unknown as { profile: unknown }).profile = { ...profile, impersonating: false };
      expect(source.getImpersonationMeta()).toBeNull();
    });

    it('returns null when there is no profile at all', () => {
      expect(source.getImpersonationMeta()).toBeNull();
    });

    it('supportsImpersonation() is always true for local/cookie auth', () => {
      expect(source.supportsImpersonation()).toBe(true);
    });
  });

  /** Bridges an Observable's first emission to a Promise so `await` can sit next to `httpMock.flush()`. */
  function firstEmission<T>(obs: { subscribe: (cb: (v: T) => void) => void }): Promise<T> {
    return new Promise(resolve => obs.subscribe(resolve));
  }
});
