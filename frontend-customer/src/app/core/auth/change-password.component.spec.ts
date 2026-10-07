import { beforeEach, describe, expect, it, type MockedObject, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { ChangePasswordComponent, changePasswordErrorMessage, passwordChangeProblem } from './change-password.component';

describe('passwordChangeProblem', () => {
    it('stays silent until every field is filled', () => {
        expect(passwordChangeProblem('', '', '')).toBeNull();
        expect(passwordChangeProblem('old', 'long-enough-1', '')).toBeNull();
    });
    it('names the first violated rule', () => {
        expect(passwordChangeProblem('old', 'short', 'short')).toMatch(/at least 8/);
        expect(passwordChangeProblem('old', 'long-enough-1', 'long-enough-2')).toMatch(/do not match/);
        expect(passwordChangeProblem('same-secret-1', 'same-secret-1', 'same-secret-1')).toMatch(/differ/);
        expect(passwordChangeProblem('old', 'long-enough-1', 'long-enough-1')).toBeNull();
    });
});

describe('changePasswordErrorMessage', () => {
    it('passes the backend reason of a 400 through and maps session/availability failures', () => {
        expect(changePasswordErrorMessage({ status: 400, error: { message: 'The current password is incorrect' } }))
            .toBe('The current password is incorrect');
        expect(changePasswordErrorMessage({ status: 401 })).toMatch(/sign in again/);
        expect(changePasswordErrorMessage({ status: 503 })).toMatch(/unavailable/);
    });
});

describe('ChangePasswordComponent', () => {
    let auth: MockedObject<Pick<AuthService, 'changePassword' | 'logout' | 'hasRole' | 'getEntityId'>>;
    let router: Router;

    function create() {
        const fixture = TestBed.createComponent(ChangePasswordComponent);
        fixture.detectChanges();
        return fixture.componentInstance;
    }

    beforeEach(() => {
        auth = { changePassword: vi.fn(), logout: vi.fn(), hasRole: vi.fn().mockReturnValue(false), getEntityId: vi.fn().mockReturnValue('ent-1') };
        TestBed.configureTestingModule({
            imports: [ChangePasswordComponent],
            providers: [provideZonelessChangeDetection(), provideRouter([]), { provide: AuthService, useValue: auth }],
        });
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate').mockResolvedValue(true);
    });

    it('does not call the backend while the form is not ready', () => {
        const c = create();
        c.current = 'changeme'; c.next = 'short'; c.confirm = 'short';
        c.submit();
        expect(auth.changePassword).not.toHaveBeenCalled();
    });

    it('submits and continues to the dashboard', () => {
        auth.changePassword.mockReturnValue(of(void 0));
        const c = create();
        c.current = 'changeme'; c.next = 'long-enough-1'; c.confirm = 'long-enough-1';

        c.submit();

        expect(auth.changePassword).toHaveBeenCalledWith('changeme', 'long-enough-1');
        expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
        expect(c.next).toBe('');
    });

    it('shows the backend reason inline and clears the current password when refused', () => {
        auth.changePassword.mockReturnValue(throwError(() => ({ status: 400, error: { message: 'The current password is incorrect' } })));
        const c = create();
        c.current = 'wrong'; c.next = 'long-enough-1'; c.confirm = 'long-enough-1';

        c.submit();

        expect(router.navigate).not.toHaveBeenCalled();
        expect(c.current).toBe('');
        expect(c.loading).toBe(false);
        expect(c.serverError).toBe('The current password is incorrect');
    });
});
