import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AuthService } from './auth.service';
import { AUTH_CONFIG, AuthConfig } from './auth-config';
import { TokenSource } from './token-source';

describe('AuthService', () => {
    let service: AuthService;
    let tokenSource: MockedObject<TokenSource>;
    let config: AuthConfig;

    function configure(cfg: AuthConfig): void {
        TestBed.resetTestingModule();
        config = cfg;
        tokenSource = {
            initialize: vi.fn().mockName("TokenSource.initialize"),
            getToken: vi.fn().mockName("TokenSource.getToken"),
            setToken: vi.fn().mockName("TokenSource.setToken"),
            clearToken: vi.fn().mockName("TokenSource.clearToken"),
            acquireToken$: vi.fn().mockName("TokenSource.acquireToken$"),
            acquireTokenWithClaims: vi.fn().mockName("TokenSource.acquireTokenWithClaims"),
            isAuthenticated: vi.fn().mockName("TokenSource.isAuthenticated"),
            login: vi.fn().mockName("TokenSource.login"),
            loginWithCredentials: vi.fn().mockName("TokenSource.loginWithCredentials"),
            logout: vi.fn().mockName("TokenSource.logout"),
            enterImpersonation: vi.fn().mockName("TokenSource.enterImpersonation"),
            exitImpersonation: vi.fn().mockName("TokenSource.exitImpersonation"),
            getImpersonationMeta: vi.fn().mockName("TokenSource.getImpersonationMeta"),
            supportsImpersonation: vi.fn().mockName("TokenSource.supportsImpersonation"),
            getProfile: vi.fn().mockName("TokenSource.getProfile")
        };

        TestBed.configureTestingModule({
            providers: [
                AuthService,
                { provide: TokenSource, useValue: tokenSource },
                { provide: AUTH_CONFIG, useValue: config },
            ],
        });
        service = TestBed.inject(AuthService);
    }

    beforeEach(() => {
        configure({
            mode: 'LOCAL',
            authority: '',
            clientId: '',
            scopes: [],
            localRegistrationEnabled: true,
            twoFactorPageEnabled: false,
            requireTwoFactorEnrolment: false,
            mfaSetupUrl: 'https://mysignins.microsoft.com/security-info',
        });
    });

    describe('token storage delegation', () => {
        it('delegates getToken() to the TokenSource', () => {
            tokenSource.getToken.mockReturnValue('a-token');
            expect(service.getToken()).toBe('a-token');
            expect(tokenSource.getToken).toHaveBeenCalled();
        });

        it('delegates setToken() to the TokenSource', () => {
            service.setToken('new-token');
            expect(tokenSource.setToken).toHaveBeenCalledWith('new-token');
        });

        it('delegates clearToken() to the TokenSource', () => {
            service.clearToken();
            expect(tokenSource.clearToken).toHaveBeenCalled();
        });

        it('delegates acquireToken$() to the TokenSource', () => {
            tokenSource.acquireToken$.mockReturnValue(of('acquired'));
            let result: string | null | undefined;
            service.acquireToken$().subscribe(t => (result = t));
            expect(result).toBe('acquired');
        });

        it('delegates acquireTokenWithClaims() to the TokenSource', () => {
            service.acquireTokenWithClaims('base64claims');
            expect(tokenSource.acquireTokenWithClaims).toHaveBeenCalledWith('base64claims');
        });
    });

    describe('isAuthenticated()', () => {
        it('wraps the synchronous TokenSource result in an Observable', () => {
            tokenSource.isAuthenticated.mockReturnValue(true);
            let emitted: boolean | undefined;
            service.isAuthenticated().subscribe(v => (emitted = v));
            expect(emitted).toBe(true);
        });

        it('isAuthenticatedSync() reads the TokenSource synchronously', () => {
            tokenSource.isAuthenticated.mockReturnValue(false);
            expect(service.isAuthenticatedSync()).toBe(false);
        });
    });

    describe('JWT / profile claims', () => {
        it('getUserRoles() returns [] when there is no profile', () => {
            tokenSource.getProfile.mockReturnValue(null);
            expect(service.getUserRoles()).toEqual([]);
        });

        it('getUserRoles() reads the top-level roles claim', () => {
            tokenSource.getProfile.mockReturnValue({ roles: ['CUSTOMER_ADMIN', 'INVESTOR'] });
            expect(service.getUserRoles()).toEqual(['CUSTOMER_ADMIN', 'INVESTOR']);
        });

        it('getUserRoles() falls back to realm_access.roles', () => {
            tokenSource.getProfile.mockReturnValue({ realm_access: { roles: ['LEGACY_ROLE'] } });
            expect(service.getUserRoles()).toEqual(['LEGACY_ROLE']);
        });

        it('getUserRoles() returns [] when the roles claim is not an array', () => {
            tokenSource.getProfile.mockReturnValue({ roles: 'not-an-array' });
            expect(service.getUserRoles()).toEqual([]);
        });

        it('getEntityId() reads entityId', () => {
            tokenSource.getProfile.mockReturnValue({ entityId: 'ent-1' });
            expect(service.getEntityId()).toBe('ent-1');
        });

        it('getEntityId() falls back to entity_id', () => {
            tokenSource.getProfile.mockReturnValue({ entity_id: 'ent-2' });
            expect(service.getEntityId()).toBe('ent-2');
        });

        it('getEntityId() returns null with no profile', () => {
            tokenSource.getProfile.mockReturnValue(null);
            expect(service.getEntityId()).toBeNull();
        });

        it('getUserEmail() prefers the email claim over preferred_username/upn', () => {
            tokenSource.getProfile.mockReturnValue({
                email: 'a@example.com',
                preferred_username: 'b@example.com',
                upn: 'c@example.com',
            });
            expect(service.getUserEmail()).toBe('a@example.com');
        });

        it('getUserEmail() falls back to preferred_username then upn', () => {
            tokenSource.getProfile.mockReturnValue({ upn: 'c@example.com' });
            expect(service.getUserEmail()).toBe('c@example.com');
        });

        it('getUserName() reads the name claim', () => {
            tokenSource.getProfile.mockReturnValue({ name: 'Jane Doe' });
            expect(service.getUserName()).toBe('Jane Doe');
        });

        it('hasRole() checks membership in getUserRoles()', () => {
            tokenSource.getProfile.mockReturnValue({ roles: ['CUSTOMER_ADMIN'] });
            expect(service.hasRole('CUSTOMER_ADMIN')).toBe(true);
            expect(service.hasRole('REGISTRY_ADMIN')).toBe(false);
        });
    });

    describe('isImpersonating() — regression test', () => {
        // Real bug fixed this session: this must read the `impersonating` boolean field the backend
        // returns in the session profile, NOT a JWT `imp` claim (which never existed under the
        // cookie-session model and would always be undefined).
        it('returns true when profile.impersonating is true', () => {
            tokenSource.getProfile.mockReturnValue({ impersonating: true });
            expect(service.isImpersonating()).toBe(true);
        });

        it('returns false when profile.impersonating is false', () => {
            tokenSource.getProfile.mockReturnValue({ impersonating: false });
            expect(service.isImpersonating()).toBe(false);
        });

        it('returns false when there is no profile at all', () => {
            tokenSource.getProfile.mockReturnValue(null);
            expect(service.isImpersonating()).toBe(false);
        });

        it('ignores an unrelated "imp" claim — only "impersonating" counts', () => {
            tokenSource.getProfile.mockReturnValue({ imp: true, impersonating: false });
            expect(service.isImpersonating()).toBe(false);
        });
    });

    describe('impersonation delegation', () => {
        it('supportsImpersonation() delegates to the TokenSource', () => {
            tokenSource.supportsImpersonation.mockReturnValue(true);
            expect(service.supportsImpersonation()).toBe(true);
        });

        it('getImpersonationMeta() delegates to the TokenSource', () => {
            const meta = { entityId: 'ent-1', entityName: 'Acme GmbH' };
            tokenSource.getImpersonationMeta.mockReturnValue(meta);
            expect(service.getImpersonationMeta()).toBe(meta);
        });

        it('enterImpersonation() delegates to and returns the TokenSource Observable', () => {
            tokenSource.enterImpersonation.mockReturnValue(of(void 0));
            let completed = false;
            service.enterImpersonation('tok', 'ent-1', 'Acme').subscribe(() => (completed = true));
            expect(tokenSource.enterImpersonation).toHaveBeenCalledWith('tok', 'ent-1', 'Acme');
            expect(completed).toBe(true);
        });

        it('exitImpersonation() delegates to and returns the TokenSource Observable', () => {
            tokenSource.exitImpersonation.mockReturnValue(of(void 0));
            let completed = false;
            service.exitImpersonation().subscribe(() => (completed = true));
            expect(tokenSource.exitImpersonation).toHaveBeenCalled();
            expect(completed).toBe(true);
        });
    });

    describe('sign-in mode', () => {
        it('isEntraMode() is false in LOCAL mode', () => {
            expect(service.isEntraMode()).toBe(false);
        });

        it('isLocalRegistrationEnabled() reflects the config', () => {
            expect(service.isLocalRegistrationEnabled()).toBe(true);
        });
    });

    describe('ENTRA mode config', () => {
        beforeEach(() => {
            configure({
                mode: 'ENTRA',
                authority: 'https://login.microsoftonline.com/tenant',
                clientId: 'spa-client',
                scopes: ['api://x/access_as_user'],
                localRegistrationEnabled: false,
                twoFactorPageEnabled: true,
                requireTwoFactorEnrolment: true,
                mfaSetupUrl: 'https://mysignins.microsoft.com/security-info',
            });
        });

        it('isEntraMode() is true', () => {
            expect(service.isEntraMode()).toBe(true);
        });

        it('isLocalRegistrationEnabled() is false', () => {
            expect(service.isLocalRegistrationEnabled()).toBe(false);
        });
    });

    describe('navigation helpers', () => {
        it('login() delegates to the TokenSource', () => {
            service.login();
            expect(tokenSource.login).toHaveBeenCalled();
        });

        it('loginWithCredentials() delegates to and returns the TokenSource Observable', () => {
            tokenSource.loginWithCredentials.mockReturnValue(of(void 0));
            let completed = false;
            service.loginWithCredentials('a@b.com', 'pw').subscribe(() => (completed = true));
            expect(tokenSource.loginWithCredentials).toHaveBeenCalledWith('a@b.com', 'pw');
            expect(completed).toBe(true);
        });

        it('logout() delegates to the TokenSource', () => {
            service.logout();
            expect(tokenSource.logout).toHaveBeenCalled();
        });
    });
});
