import { beforeEach, describe, expect, it, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SUPPRESS_ERROR_TOAST } from '@registerwerk/ui';
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
            providers: [MsalTokenSource, { provide: AUTH_CONFIG, useValue: entraConfig }, provideHttpClient(), provideHttpClientTesting()],
        });
        source = TestBed.inject(MsalTokenSource);
    });

    describe('local-only operations — reject rather than no-op', () => {
        // TokenSource's Javadoc-equivalent is explicit: these throw/error so a caller that has not
        // been updated for Entra mode fails loudly instead of silently appearing to succeed.

        it('loginWithCredentials() errors — password sign-in is unavailable under Entra', async () => {
            source.loginWithCredentials('a@example.com', 'secret').subscribe({
                next: () => { throw new Error('expected an error, got a value'); },
                error: err => {
                    expect(err).toBeInstanceOf(Error);
                    expect((err as Error).message).toContain('Microsoft Entra');
                    ;
                },
            });
        });

        it('enterImpersonation() errors — impersonation is unavailable under Entra', async () => {
            source.enterImpersonation('tok', 'ent-1', 'Acme').subscribe({
                next: () => { throw new Error('expected an error, got a value'); },
                error: err => {
                    expect(err).toBeInstanceOf(Error);
                    expect((err as Error).message).toContain('Impersonation is unavailable');
                    ;
                },
            });
        });

        it('setToken() throws synchronously — Entra issues tokens, the app cannot mint them', () => {
            expect(() => source.setToken('anything')).toThrowError(/Microsoft Entra/);
        });

        it('exitImpersonation() resolves — nothing to restore, impersonation can never have started', async () => {
            source.exitImpersonation().subscribe({
                next: value => {
                    expect(value).toBeUndefined();
                    ;
                },
                error: () => { throw new Error('expected exitImpersonation() to complete normally'); },
            });
        });

        it('getImpersonationMeta() is always null', () => {
            expect(source.getImpersonationMeta()).toBeNull();
        });

        it('supportsImpersonation() is always false', () => {
            expect(source.supportsImpersonation()).toBe(false);
        });

        it('clearToken() drops the cached token without throwing (used by the 401 handler)', () => {
            (source as unknown as {
                cachedToken: string | null;
            }).cachedToken = 'x';
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
            (source as unknown as {
                cachedToken: string | null;
            }).cachedToken = token;

            const profile = source.getProfile();
            expect(profile).not.toBeNull();
            expect(profile!['roles']).toEqual(['INVESTOR']);
            expect(profile!['entityId']).toBe('ent-1');
            expect(profile!['email']).toBe('a@example.com');
        });

        it('returns null rather than throwing on a malformed cached token', () => {
            (source as unknown as {
                cachedToken: string | null;
            }).cachedToken = 'not-a-jwt';
            expect(source.getProfile()).toBeNull();
        });
    });

    describe('initialize() - the profile comes from the backend, not from the raw Entra token', () => {
        // An Entra access token carries neither the account's roles (they live in app_user, never in the IdP)
        // nor its legal entity; the backend resolves both and `GET /auth/session` returns them. Reading the
        // raw token alone left every role-gated route and menu entry empty in Entra mode.
        const entraToken = () => fakeJwt({ oid: 'entra-oid', preferred_username: 'a@example.com', name: 'Entra Name' });
        const account = { homeAccountId: 'h', username: 'a@example.com' };
        const sessionProfile = {
            userId: 'u1', roles: ['INVESTOR'], email: 'a@example.com', name: 'Anna', entityId: 'ent-1',
            entityName: null, impersonating: false, expiresAt: 1,
        };
        let http: HttpTestingController;

        function stubMsal(silent: () => Promise<{ accessToken: string }>) {
            vi.spyOn(source as unknown as { loadMsal(): Promise<unknown> }, 'loadMsal').mockResolvedValue({
                PublicClientApplication: class {
                    initialize = vi.fn(async () => undefined);
                    handleRedirectPromise = vi.fn(async () => null);
                    getActiveAccount = vi.fn(() => account);
                    getAllAccounts = vi.fn(() => [account]);
                    setActiveAccount = vi.fn();
                    acquireTokenSilent = vi.fn(silent);
                },
                InteractionRequiredAuthError: class extends Error {},
            });
        }
        const sessionRequest = () => vi.waitFor(() => http.expectOne(r => r.url.endsWith('/auth/session')));

        beforeEach(() => { http = TestBed.inject(HttpTestingController); });

        it('uses the roles and entity of GET /auth/session over the token claims', async () => {
            stubMsal(async () => ({ accessToken: entraToken() }));

            const init = source.initialize();
            const req = await sessionRequest();
            // the probe answers its caller: a 403 for a not-yet-provisioned account must not toast "Access denied"
            expect(req.request.context.get(SUPPRESS_ERROR_TOAST)).toBe(true);
            req.flush(sessionProfile);
            await init;

            expect(source.getProfile()).toMatchObject({ roles: ['INVESTOR'], entityId: 'ent-1', name: 'Anna', email: 'a@example.com' });
        });

        it('falls back to the token claims when the session call fails (account not provisioned yet)', async () => {
            stubMsal(async () => ({ accessToken: entraToken() }));

            const init = source.initialize();
            (await sessionRequest()).flush({ message: 'no account' }, { status: 403, statusText: 'Forbidden' });
            await init;

            expect(source.isAuthenticated()).toBe(true);
            expect(source.getProfile()).toMatchObject({ name: 'Entra Name', preferred_username: 'a@example.com' });
            expect(source.getProfile()?.['roles']).toBeUndefined();
        });

        it('asks the backend nothing when no token could be acquired silently', async () => {
            stubMsal(async () => { throw new Error('login_required'); });

            await source.initialize();

            http.expectNone(r => r.url.endsWith('/auth/session'));
            expect(source.isAuthenticated()).toBe(false);
            expect(source.getProfile()).toBeNull();
        });
    });

    describe('getToken() / isAuthenticated()', () => {
        it('getToken() reflects the cached token', () => {
            (source as unknown as {
                cachedToken: string | null;
            }).cachedToken = 'the-token';
            expect(source.getToken()).toBe('the-token');
        });

        it('isAuthenticated() requires both an account and a cached token', () => {
            expect(source.isAuthenticated()).toBe(false);

            (source as unknown as {
                account: unknown;
            }).account = { username: 'a@example.com' };
            expect(source.isAuthenticated()).toBe(false); // still no token

            (source as unknown as {
                cachedToken: string | null;
            }).cachedToken = 'tok';
            expect(source.isAuthenticated()).toBe(true);
        });
    });

    describe('acquireToken$() with no account', () => {
        it('resolves null rather than calling into MSAL when no account is active', async () => {
            source.acquireToken$().subscribe(token => {
                expect(token).toBeNull();
                ;
            });
        });
    });
});
