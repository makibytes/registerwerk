import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { WorkspaceService } from './workspace.service';

describe('WorkspaceService', () => {
    let roles: Set<string>;
    let service: WorkspaceService;
    let storedWorkspace: string | null;

    const storage = {
        getItem: vi.fn(() => storedWorkspace),
        setItem: vi.fn((_key: string, value: string) => { storedWorkspace = value; }),
        removeItem: vi.fn(() => { storedWorkspace = null; }),
    };

    beforeEach(() => {
        roles = new Set<string>();
        storedWorkspace = null;
        vi.stubGlobal('localStorage', storage);
        window.localStorage.removeItem('rw.workspace');
        TestBed.configureTestingModule({
            providers: [
                WorkspaceService,
                { provide: AuthService, useValue: { hasRole: (role: string) => roles.has(role) } },
            ],
        });
        service = TestBed.inject(WorkspaceService);
    });

    afterEach(() => {
        window.localStorage.removeItem('rw.workspace');
        vi.unstubAllGlobals();
    });

    it('shows only role-authorized links inside a shared issuer workspace', () => {
        roles.add('COMPANY_ADMIN');

        const workspace = service.activeWorkspace();

        expect(workspace.key).toBe('ISSUER');
        expect(workspace.links.map((link) => link.route)).toEqual([
            '/dashboard',
            '/publisher',
            '/company-admin',
            '/marketplace',
        ]);
        expect(workspace.links.some((link) => link.route === '/issuances')).toBe(false);
    });

    it('ignores attempts to select a workspace the current roles cannot access', () => {
        roles.add('INVESTOR');

        service.setWorkspace('ISSUER');

        expect(service.activeWorkspace().key).toBe('INVESTOR');
        expect(window.localStorage.getItem('rw.workspace')).toBeNull();
    });

    it('restores an eligible stored workspace and filters its protected links', () => {
        roles.add('TRADER');
        roles.add('INVESTOR');
        window.localStorage.setItem('rw.workspace', 'INVESTOR');

        const workspace = service.activeWorkspace();

        expect(workspace.key).toBe('INVESTOR');
        expect(workspace.links.some((link) => link.route === '/investments')).toBe(true);
        expect(service.eligibleWorkspaces().map((candidate) => candidate.key)).toEqual(['TRADER', 'INVESTOR']);
    });
});
