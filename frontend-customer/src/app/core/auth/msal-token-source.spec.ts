import { TestBed } from '@angular/core/testing';
import { MsalTokenSource } from './msal-token-source';
import { AUTH_CONFIG, AuthConfig } from './auth-config';

/** Builds a minimal, unsigned-looking JWT with the given payload — enough for atob() decoding. */
function fakeJwt(payload: Record<string, unknown>): string {
  const base64 = (obj: unknown) => btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_');
  return `${base64({ alg: 'RS256', typ: 'JWT' })}.${base64(payload)}.signature`;
}

describe('MsalTokenSource', () => {
  let source: MsalTokenSource;

  const entraConfig: AuthConfig = {
    mode: 'ENTRA',
    authority: 'https://login.microsoftonline.com/tenant-id',
    clientId: 'spa-client-id',
    scopes: ['api://backend/access_as_user'],
    localRegistrationEnabled: false,
    twoFactorPageEnabled: true,
    requireTwoFactorEnrolment: true,
    mfaSetupUrl: 'https://mysignins.microsoft.com/security-info',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [MsalTokenSource, { provide: AUTH_CONFIG, useValue: entraConfig }],
    });
    source = TestBed.inject(MsalTokenSource);
  });

  describe('local-only operations — reject rather than no-op', () => {
    // TokenSource's Javadoc-equivalent is explicit: these throw/error so a caller that has not
    // been updated for Entra mode fails loudly instead of silently appearing to succeed.

    it('loginWithCredentials() errors — password sign-in is unavailable under Entra', done => {
      source.loginWithCredentials('a@example.com', 'secret').subscribe({
        next: () => fail('expected an error, got a value'),
        error: err => {
          expect(err).toBeInstanceOf(Error);
          expect((err as Error).message).toContain('Microsoft Entra');
          done();
        },
      });
    });

    it('enterImpersonation() errors — impersonation is unavailable under Entra', done => {
      source.enterImpersonation('tok', 'ent-1', 'Acme').subscribe({
        next: () => fail('expected an error, got a value'),
        error: err => {
          expect(err).toBeInstanceOf(Error);
          expect((err as Error).message).toContain('Impersonation is unavailable');
          done();
        },
      });
    });

    it('setToken() throws synchronously — Entra issues tokens, the app cannot mint them', () => {
      expect(() => source.setToken('anything')).toThrowError(/Microsoft Entra/);
    });

    it('exitImpersonation() resolves — nothing to restore, impersonation can never have started', done => {
      source.exitImpersonation().subscribe({
        next: value => {
          expect(value).toBeUndefined();
          done();
        },
        error: () => fail('expected exitImpersonation() to complete normally'),
      });
    });

    it('getImpersonationMeta() is always null', () => {
      expect(source.getImpersonationMeta()).toBeNull();
    });

    it('supportsImpersonation() is always false', () => {
      expect(source.supportsImpersonation()).toBe(false);
    });

    it('clearToken() drops the cached token without throwing (used by the 401 handler)', () => {
      (source as unknown as { cachedToken: string | null }).cachedToken = 'x';
      expect(() => source.clearToken()).not.toThrow();
      expect(source.getToken()).toBeNull();
    });
  });

  describe('getProfile() — JWT decoding', () => {
    it('returns null when there is no cached token', () => {
      expect(source.getProfile()).toBeNull();
    });

    it('decodes the cached access token payload', () => {
      const token = fakeJwt({ roles: ['INVESTOR'], entityId: 'ent-1', email: 'a@example.com' });
      (source as unknown as { cachedToken: string | null }).cachedToken = token;

      const profile = source.getProfile();
      expect(profile).not.toBeNull();
      expect(profile!['roles']).toEqual(['INVESTOR']);
      expect(profile!['entityId']).toBe('ent-1');
      expect(profile!['email']).toBe('a@example.com');
    });

    it('returns null rather than throwing on a malformed cached token', () => {
      (source as unknown as { cachedToken: string | null }).cachedToken = 'not-a-jwt';
      expect(source.getProfile()).toBeNull();
    });
  });

  describe('getToken() / isAuthenticated()', () => {
    it('getToken() reflects the cached token', () => {
      (source as unknown as { cachedToken: string | null }).cachedToken = 'the-token';
      expect(source.getToken()).toBe('the-token');
    });

    it('isAuthenticated() requires both an account and a cached token', () => {
      expect(source.isAuthenticated()).toBe(false);

      (source as unknown as { account: unknown }).account = { username: 'a@example.com' };
      expect(source.isAuthenticated()).toBe(false); // still no token

      (source as unknown as { cachedToken: string | null }).cachedToken = 'tok';
      expect(source.isAuthenticated()).toBe(true);
    });
  });

  describe('acquireToken$() with no account', () => {
    it('resolves null rather than calling into MSAL when no account is active', done => {
      source.acquireToken$().subscribe(token => {
        expect(token).toBeNull();
        done();
      });
    });
  });
});
