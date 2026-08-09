import { afterEach, beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HandoffComponent } from './handoff.component';
import { AuthService } from '../../core/auth/auth.service';

describe('HandoffComponent', () => {
    let authService: MockedObject<Pick<AuthService, 'supportsImpersonation' | 'enterImpersonation'>>;
    let router: MockedObject<Pick<Router, 'navigate'>>;

    function createComponent() {
        const fixture = TestBed.createComponent(HandoffComponent);
        fixture.detectChanges();
        return fixture;
    }

    beforeEach(() => {
        authService = {
            supportsImpersonation: vi.fn().mockName("AuthService.supportsImpersonation"),
            enterImpersonation: vi.fn().mockName("AuthService.enterImpersonation")
        };
        router = {
            navigate: vi.fn().mockName("Router.navigate")
        };

        TestBed.configureTestingModule({
            imports: [HandoffComponent],
            providers: [
                provideZonelessChangeDetection(),
                { provide: AuthService, useValue: authService },
                { provide: Router, useValue: router },
            ],
        });
    });

    afterEach(() => {
        history.replaceState(null, '', window.location.pathname);
    });

    it('shows the unsupported notice and strips the fragment when Entra mode does not support impersonation', () => {
        window.location.hash = '#token=abc&entityId=ent-1&entityName=Acme';
        authService.supportsImpersonation.mockReturnValue(false);
        const replaceStateSpy = vi.spyOn(history, 'replaceState');

        const fixture = createComponent();

        expect(fixture.componentInstance.unsupported).toBe(true);
        expect(authService.enterImpersonation).not.toHaveBeenCalled();
        expect(replaceStateSpy).toHaveBeenCalled();
    });

    it('exchanges the token via AuthService and navigates to /dashboard on success', () => {
        window.location.hash = '#token=impersonation-tok&entityId=ent-1&entityName=Acme%20GmbH';
        authService.supportsImpersonation.mockReturnValue(true);
        authService.enterImpersonation.mockReturnValue(of(void 0));

        const fixture = createComponent();

        expect(fixture.componentInstance.unsupported).toBe(false);
        expect(authService.enterImpersonation).toHaveBeenCalledWith('impersonation-tok', 'ent-1', 'Acme GmbH');
        expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
    });

    it('shows a recoverable failure notice when the token exchange fails', () => {
        window.location.hash = '#token=bad-tok&entityId=ent-1&entityName=Acme';
        authService.supportsImpersonation.mockReturnValue(true);
        authService.enterImpersonation.mockReturnValue(throwError(() => new Error('exchange failed')));

        const fixture = createComponent();

        expect(fixture.componentInstance.failed).toBe(true);
        expect(router.navigate).not.toHaveBeenCalled();
    });

    it('shows a failure notice when the fragment carries no token', () => {
        window.location.hash = '';
        authService.supportsImpersonation.mockReturnValue(true);

        const fixture = createComponent();

        expect(authService.enterImpersonation).not.toHaveBeenCalled();
        expect(fixture.componentInstance.failed).toBe(true);
        expect(router.navigate).not.toHaveBeenCalled();
    });
});
