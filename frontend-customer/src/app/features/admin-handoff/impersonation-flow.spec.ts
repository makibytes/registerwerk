import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { SelectCompanyComponent } from './select-company.component';
import { HandoffComponent } from './handoff.component';
import { AuthService } from '../../core/auth/auth.service';
import { AUTH_CONFIG } from '../../core/auth/auth-config';
import { CookieTokenSource } from '../../core/auth/cookie-token-source';
import { TokenSource } from '../../core/auth/token-source';
import { errorInterceptor } from '../../core/interceptors/error.interceptor';
import { environment } from '../../../environments/environment';

/**
 * The impersonation journey end to end through the REAL AdminService, AuthService, CookieTokenSource
 * and error interceptor, with only the network replaced. The component specs mock the services they
 * depend on, so they stay green when the services, the wire contract and the interceptor drift apart
 * — which is exactly how this feature has broken before. This spec is the one that must fail then.
 *
 * Wire contract pinned here (mirrors the backend's AdminImpersonationController / AuthController):
 *   GET  /entities                       -> page of companies
 *   POST /auth/step-up      {code,method}-> {stepUpToken}
 *   POST /impersonation     Bearer step-up, {entityId,reason,ticket?} -> {handoffUrl,...}
 *   POST /public/auth/impersonate {code} -> session profile (cookie set by the server)
 */
describe('impersonation journey (real services, mocked network)', () => {
    const api = environment.apiUrl;
    let http: HttpTestingController;
    let navigate: ReturnType<typeof vi.fn>;
    let toast: ReturnType<typeof vi.fn>;

    const company = { id: 'ent-1', currentName: 'Acme GmbH', entityNumber: 'HRB1', type: 'ISSUER', status: 'ACTIVE', kycStatus: 'APPROVED' };
    const profile = {
        userId: 'u1', roles: ['REGISTRY_ADMIN'], email: 'op@x', name: 'Op', entityId: 'ent-1', entityName: 'Acme GmbH',
        impersonating: true, impersonationMode: 'READ_ONLY', expiresAt: 0,
    };

    beforeEach(() => {
        navigate = vi.fn().mockResolvedValue(true);
        toast = vi.fn();
        TestBed.configureTestingModule({
            providers: [
                provideZonelessChangeDetection(),
                provideHttpClient(withInterceptors([errorInterceptor])),
                provideHttpClientTesting(),
                { provide: AUTH_CONFIG, useValue: { mode: 'LOCAL', localRegistrationEnabled: true } },
                { provide: TokenSource, useClass: CookieTokenSource },
                { provide: Router, useValue: { navigate, url: '/select-company' } },
                { provide: MatSnackBar, useValue: { open: toast } },
            ],
        });
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    function openForm(): { fixture: ReturnType<typeof TestBed.createComponent<SelectCompanyComponent>>; root: HTMLElement } {
        const fixture = TestBed.createComponent(SelectCompanyComponent);
        fixture.detectChanges();
        http.expectOne(r => r.url === `${api}/entities`).flush({ content: [company], totalElements: 1, totalPages: 1, number: 0, size: 50 });
        fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        (root.querySelector('button.entity-row') as HTMLButtonElement).click();
        fixture.detectChanges();
        return { fixture, root };
    }

    function fill(root: HTMLElement, label: string, value: string): void {
        const input = root.querySelector(`input[aria-label="${label}"]`) as HTMLInputElement;
        input.value = value;
        input.dispatchEvent(new Event('input'));
    }

    async function submit(fixture: ReturnType<typeof openForm>['fixture'], root: HTMLElement): Promise<void> {
        // ngModel seeds its control in a microtask; typing before that would be overwritten with ''.
        await fixture.whenStable();
        fill(root, 'Reason', 'Customer asked for help with an order');
        fill(root, 'Authenticator code', '123456');
        fixture.detectChanges();
        await fixture.whenStable();
        (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    }

    it('picks a company, steps up, starts the session, exchanges the code and lands on the dashboard', async () => {
        const { fixture, root } = openForm();
        await submit(fixture, root);

        const stepUp = http.expectOne(`${api}/auth/step-up`);
        expect(stepUp.request.body).toEqual({ code: '123456', method: 'TOTP' });
        stepUp.flush({ stepUpToken: 'su-tok' });

        const imp = http.expectOne(`${api}/impersonation`);
        expect(imp.request.headers.get('Authorization')).toBe('Bearer su-tok');
        expect(imp.request.body).toEqual({ entityId: 'ent-1', reason: 'Customer asked for help with an order' });
        imp.flush({
            sessionId: 's1', mode: 'READ_ONLY', expiresAt: 'x', entityId: 'ent-1', entityName: 'Acme GmbH',
            handoffUrl: 'http://localhost:44201/admin/handoff#code=one-time&entityId=ent-1&entityName=Acme%20GmbH',
        });

        const exchange = http.expectOne(`${api}/public/auth/impersonate`);
        expect(exchange.request.body).toEqual({ code: 'one-time' });
        exchange.flush(profile);

        expect(navigate).toHaveBeenCalledWith(['/dashboard']);
        const auth = TestBed.inject(AuthService);
        expect(auth.isImpersonating()).toBe(true);
        expect(auth.isImpersonationReadOnly()).toBe(true);
        expect(toast).not.toHaveBeenCalled();
    });

    it('an unenrolled operator is told why, inline, with no generic toast on top of it', async () => {
        const { fixture, root } = openForm();
        await submit(fixture, root);

        http.expectOne(`${api}/auth/step-up`).flush(
            { status: 403, code: 'STEP_UP_ENROLMENT_REQUIRED', message: 'Step-up requires TOTP enrolment.' },
            { status: 403, statusText: 'Forbidden' });
        fixture.detectChanges();

        http.expectNone(`${api}/impersonation`);
        expect(toast).not.toHaveBeenCalled();
        expect(root.querySelector('[role="alert"]')?.textContent).toContain('no authenticator app enrolled');
        expect(root.querySelector('[aria-label="Start support session"]')).not.toBeNull();
        expect(navigate).not.toHaveBeenCalled();
    });

    it('a refusal by /impersonation (for example a suspended company) is shown inline too', async () => {
        const { fixture, root } = openForm();
        await submit(fixture, root);
        http.expectOne(`${api}/auth/step-up`).flush({ stepUpToken: 'su-tok' });
        http.expectOne(`${api}/impersonation`).flush(
            { status: 409, message: 'Cannot impersonate a suspended entity' }, { status: 409, statusText: 'Conflict' });
        fixture.detectChanges();

        expect(root.querySelector('[role="alert"]')?.textContent).toContain('Cannot impersonate a suspended entity');
        expect(toast).not.toHaveBeenCalled();
    });

    it('an expired or reused handoff code reaches the form as a message, not a sign-in redirect', async () => {
        const { fixture, root } = openForm();
        await submit(fixture, root);
        http.expectOne(`${api}/auth/step-up`).flush({ stepUpToken: 'su-tok' });
        http.expectOne(`${api}/impersonation`).flush({
            sessionId: 's1', mode: 'READ_ONLY', expiresAt: 'x', entityId: 'ent-1', entityName: 'Acme',
            handoffUrl: 'http://x/admin/handoff#code=spent',
        });
        http.expectOne(`${api}/public/auth/impersonate`).flush({}, { status: 401, statusText: 'Unauthorized' });
        fixture.detectChanges();

        expect(navigate).not.toHaveBeenCalled();
        expect(root.querySelector('[role="alert"]')).not.toBeNull();
    });
});

describe('operator handoff link (/admin/handoff#code=...)', () => {
    const api = environment.apiUrl;
    let http: HttpTestingController;
    let navigate: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        navigate = vi.fn().mockResolvedValue(true);
        TestBed.configureTestingModule({
            providers: [
                provideZonelessChangeDetection(),
                provideHttpClient(withInterceptors([errorInterceptor])),
                provideHttpClientTesting(),
                { provide: AUTH_CONFIG, useValue: { mode: 'LOCAL', localRegistrationEnabled: true } },
                { provide: TokenSource, useClass: CookieTokenSource },
                { provide: Router, useValue: { navigate } },
                { provide: MatSnackBar, useValue: { open: vi.fn() } },
            ],
        });
        http = TestBed.inject(HttpTestingController);
        window.history.replaceState(null, '', '/admin/handoff#code=one-time&entityId=ent-1&entityName=Acme%20GmbH');
    });

    afterEach(() => {
        http.verify();
        window.history.replaceState(null, '', '/');
    });

    it('exchanges the fragment code once, scrubs the URL and goes to the dashboard', () => {
        const fixture = TestBed.createComponent(HandoffComponent);
        fixture.detectChanges();

        expect(window.location.hash).toBe('');
        const exchange = http.expectOne(`${api}/public/auth/impersonate`);
        expect(exchange.request.body).toEqual({ code: 'one-time' });
        exchange.flush({ userId: 'u', roles: ['REGISTRY_ADMIN'], email: null, name: null, entityId: 'ent-1', entityName: 'Acme GmbH',
            impersonating: true, impersonationMode: 'READ_ONLY', expiresAt: 0 });
        expect(navigate).toHaveBeenCalledWith(['/dashboard']);
    });
});
