import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { roleGuard } from './role.guard';
import { AuthService } from './auth.service';
import { routes } from '../../app.routes';

describe('roleGuard', () => {
    const tree = {} as UrlTree;
    let roles: string[];

    beforeEach(() => {
        roles = [];
        TestBed.configureTestingModule({
            providers: [
                { provide: AuthService, useValue: { isAuthenticatedSync: () => true, getUserRoles: () => roles } },
                { provide: Router, useValue: { createUrlTree: vi.fn().mockReturnValue(tree) } },
            ],
        });
    });

    const run = (required: string[]) =>
        TestBed.runInInjectionContext(() => roleGuard(required)({} as never, {} as never));

    it('admits a user holding one of the roles and redirects everyone else', () => {
        roles = ['COMPANY_ADMIN'];
        expect(run(['COMPANY_ADMIN'])).toBe(true);
        roles = ['TRADER'];
        expect(run(['COMPANY_ADMIN'])).toBe(tree);
    });

    it('the /webhooks route is guarded for COMPANY_ADMIN (backend: hasRole COMPANY_ADMIN)', () => {
        const shell = routes.find(r => r.children) ?? routes[0];
        const webhooks = (shell.children ?? []).find(r => r.path === 'webhooks');
        expect(webhooks?.canActivate?.length).toBe(1);
        roles = ['TRADER'];
        const guard = webhooks!.canActivate![0] as ReturnType<typeof roleGuard>;
        expect(TestBed.runInInjectionContext(() => guard({} as never, {} as never))).toBe(tree);
        roles = ['COMPANY_ADMIN'];
        expect(TestBed.runInInjectionContext(() => guard({} as never, {} as never))).toBe(true);
    });
});
