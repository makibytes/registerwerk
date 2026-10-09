import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SelectCompanyComponent, describeSupportSessionError } from './select-company.component';
import { AuthService } from '../../core/auth/auth.service';
import { AdminService, EntityListItem, EntityPage, ImpersonateResponse } from '../../core/api/admin.service';

describe('SelectCompanyComponent', () => {
    let authService: MockedObject<Pick<AuthService, 'enterImpersonation' | 'logout'>>;
    let adminService: MockedObject<Pick<AdminService, 'listEntities' | 'startReadOnlySession'>>;
    let router: MockedObject<Pick<Router, 'navigate'>>;

    const entity: EntityListItem = {
        id: 'ent-1',
        currentName: 'Acme GmbH',
        entityNumber: 'HRB123',
        type: 'AG',
        status: 'ACTIVE',
        kycStatus: 'APPROVED',
    };

    const page: EntityPage = { content: [entity], totalElements: 1, totalPages: 1, number: 0, size: 50 };

    function createComponent() {
        const fixture = TestBed.createComponent(SelectCompanyComponent);
        fixture.detectChanges();
        return fixture;
    }

    beforeEach(() => {
        authService = {
            enterImpersonation: vi.fn().mockName("AuthService.enterImpersonation"),
            logout: vi.fn().mockName("AuthService.logout")
        };
        adminService = {
            listEntities: vi.fn().mockName("AdminService.listEntities"),
            startReadOnlySession: vi.fn().mockName("AdminService.startReadOnlySession")
        };
        router = {
            navigate: vi.fn().mockName("Router.navigate")
        };
        adminService.listEntities.mockReturnValue(of(page));

        TestBed.configureTestingModule({
            imports: [SelectCompanyComponent],
            providers: [
                provideZonelessChangeDetection(),
                { provide: AuthService, useValue: authService },
                { provide: AdminService, useValue: adminService },
                { provide: Router, useValue: router },
            ],
        });
    });

    it('loads entities on init', () => {
        const fixture = createComponent();
        const component = fixture.componentInstance;

        expect(adminService.listEntities).toHaveBeenCalledWith(undefined);
        expect(component.entities).toEqual([entity]);
        expect(component.loadingEntities).toBe(false);
    });

    it('debounces search input before reloading entities', () => {
        // Fake timers: an assertion made from a bare setTimeout nothing awaits fires after the test has
        // ended, against a later test's mocks, and fails the whole run as an unhandled error.
        vi.useFakeTimers();
        try {
            const fixture = createComponent();
            const component = fixture.componentInstance;
            adminService.listEntities.mockClear();

            component.searchQuery = 'acme';
            component.onSearch();

            // Immediately after typing, no new call yet — it is debounced (300 ms).
            expect(adminService.listEntities).not.toHaveBeenCalled();
            vi.advanceTimersByTime(299);
            expect(adminService.listEntities).not.toHaveBeenCalled();

            vi.advanceTimersByTime(1);
            expect(adminService.listEntities).toHaveBeenCalledTimes(1);
            expect(adminService.listEntities).toHaveBeenCalledWith('acme');
        } finally {
            vi.useRealTimers();
        }
    });

    describe('choosing a company', () => {
        it('replaces the list with the session form, so the form can never be below the fold', () => {
            const fixture = createComponent();
            const root: HTMLElement = fixture.nativeElement;
            expect(root.querySelectorAll('button.entity-row').length).toBe(1);
            expect(root.querySelector('[aria-label="Start support session"]')).toBeNull();

            (root.querySelector('button.entity-row') as HTMLButtonElement).click();
            fixture.detectChanges();

            expect(root.querySelector('[aria-label="Start support session"]')).not.toBeNull();
            expect(root.querySelectorAll('button.entity-row').length).toBe(0);
            expect(root.textContent).toContain('Acme GmbH');
        });

        it('"Back" returns to the list and clears the previous error', () => {
            const fixture = createComponent();
            const component = fixture.componentInstance;
            component.chooseEntity(entity);
            component.sessionError = 'old error';

            component.cancelPending();
            fixture.detectChanges();

            expect(component.pending).toBeNull();
            expect(component.sessionError).toBe('');
            expect((fixture.nativeElement as HTMLElement).querySelectorAll('button.entity-row').length).toBe(1);
        });
    });

    describe('selectEntity()', () => {
        const session: ImpersonateResponse = {
            sessionId: 'sess-1',
            mode: 'READ_ONLY',
            expiresAt: '2026-01-01T00:00:00Z',
            entityId: 'ent-1',
            entityName: 'Acme GmbH',
            handoffUrl: 'http://localhost:44201/admin/handoff#code=one-time-code&entityId=ent-1&entityName=Acme%20GmbH',
        };

        function select(component: SelectCompanyComponent) {
            component.chooseEntity(entity);
            component.reason = '  Customer asked for help with an order ';
            component.ticket = ' TCK-1 ';
            component.totpCode = '123456';
            component.selectEntity(entity);
        }

        it('starts the session via AdminService then enters impersonation via AuthService, navigating on success', () => {
            const fixture = createComponent();
            const component = fixture.componentInstance;
            adminService.startReadOnlySession.mockReturnValue(of({ handoffCode: 'one-time-code', session }));
            authService.enterImpersonation.mockReturnValue(of(void 0));

            select(component);

            expect(adminService.startReadOnlySession).toHaveBeenCalledWith(
                'ent-1', 'Customer asked for help with an order', '123456', 'TCK-1');
            expect(authService.enterImpersonation).toHaveBeenCalledWith('one-time-code', 'ent-1', 'Acme GmbH');
            expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
        });

        it('shows the failure inline and keeps the form when AuthService.enterImpersonation() fails', () => {
            const fixture = createComponent();
            const component = fixture.componentInstance;
            adminService.startReadOnlySession.mockReturnValue(of({ handoffCode: 'one-time-code', session }));
            authService.enterImpersonation.mockReturnValue(throwError(() => ({ error: { message: 'Impersonation session exchange failed' } })));

            select(component);
            fixture.detectChanges();

            expect(component.selecting).toBeNull();
            expect(router.navigate).not.toHaveBeenCalled();
            const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
            expect(alert?.textContent).toContain('Impersonation session exchange failed');
        });

        it('explains a missing authenticator enrolment instead of a bare "Access denied"', () => {
            const fixture = createComponent();
            const component = fixture.componentInstance;
            adminService.startReadOnlySession.mockReturnValue(throwError(() => ({
                status: 403,
                error: { code: 'STEP_UP_ENROLMENT_REQUIRED', message: 'Step-up requires TOTP enrolment.' },
            })));

            select(component);
            fixture.detectChanges();

            expect(authService.enterImpersonation).not.toHaveBeenCalled();
            expect(component.selecting).toBeNull();
            const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
            expect(alert?.textContent).toContain('no authenticator app enrolled');
            // The code field is cleared: a TOTP code is single-use, so retyping is required anyway.
            expect(component.totpCode).toBe('');
        });

        it('shows the backend message when the session cannot be started', () => {
            const fixture = createComponent();
            const component = fixture.componentInstance;
            adminService.startReadOnlySession.mockReturnValue(throwError(() => ({
                status: 403, error: { message: 'This TOTP code was already used. Wait for the next code.' } })));

            select(component);

            expect(component.sessionError).toBe('This TOTP code was already used. Wait for the next code.');
        });

        it('ignores a second submit while a selection is already in progress', () => {
            const fixture = createComponent();
            const component = fixture.componentInstance;

            component.selecting = 'ent-1';
            component.selectEntity(entity);

            expect(adminService.startReadOnlySession).not.toHaveBeenCalled();
        });
    });

    describe('describeSupportSessionError()', () => {
        it('covers unreachable server, throttling, backend message and an unknown shape', () => {
            expect(describeSupportSessionError({ status: 0 })).toContain('could not be reached');
            expect(describeSupportSessionError({ status: 429 })).toContain('Too many attempts');
            expect(describeSupportSessionError({ status: 403, error: { message: 'Nope' } })).toBe('Nope');
            expect(describeSupportSessionError(new Error('boom'))).toBe('boom');
            expect(describeSupportSessionError(null)).toBe('The support session could not be started.');
        });
    });

    it('logout() delegates to AuthService', () => {
        const fixture = createComponent();
        fixture.componentInstance.logout();
        expect(authService.logout).toHaveBeenCalled();
    });
});
