import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ChangePasswordComponent, passwordChangeValidator } from './change-password.component';
import { AuthService } from './auth.service';

describe('ChangePasswordComponent', () => {
    let auth: MockedObject<Pick<AuthService, 'changePassword' | 'logout'>>;
    let router: Router;

    function create() {
        const fixture = TestBed.createComponent(ChangePasswordComponent);
        fixture.detectChanges();
        return fixture;
    }

    beforeEach(() => {
        auth = { changePassword: vi.fn(), logout: vi.fn() };
        TestBed.configureTestingModule({
            imports: [ChangePasswordComponent],
            providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
        });
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate').mockResolvedValue(true);
    });

    it('is invalid until all three fields are filled, the new password is long enough and both copies match', () => {
        const c = create().componentInstance;
        expect(c.form.valid).toBe(false);
        c.form.setValue({ currentPassword: 'changeme', newPassword: 'short', confirmPassword: 'short' });
        expect(c.form.valid).toBe(false);
        c.form.setValue({ currentPassword: 'changeme', newPassword: 'long-enough-1', confirmPassword: 'long-enough-2' });
        expect(c.form.hasError('mismatch')).toBe(true);
        c.form.setValue({ currentPassword: 'changeme', newPassword: 'long-enough-1', confirmPassword: 'long-enough-1' });
        expect(c.form.valid).toBe(true);
    });

    it('refuses a new password equal to the current one', () => {
        const c = create().componentInstance;
        c.form.setValue({ currentPassword: 'same-secret-1', newPassword: 'same-secret-1', confirmPassword: 'same-secret-1' });
        expect(c.form.hasError('unchanged')).toBe(true);
        expect(passwordChangeValidator(c.form)).toEqual({ unchanged: true });
    });

    it('submits and continues to the dashboard', () => {
        auth.changePassword.mockReturnValue(of(void 0));
        const fixture = create();
        const c = fixture.componentInstance;
        c.form.setValue({ currentPassword: 'changeme', newPassword: 'long-enough-1', confirmPassword: 'long-enough-1' });

        c.submit();

        expect(auth.changePassword).toHaveBeenCalledWith('changeme', 'long-enough-1');
        expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
    });

    it('does not call the backend while the form is invalid', () => {
        const c = create().componentInstance;
        c.submit();
        expect(auth.changePassword).not.toHaveBeenCalled();
    });

    it('stays on the page and clears the current password when the backend refuses it', () => {
        auth.changePassword.mockReturnValue(throwError(() => ({ status: 400, error: { message: 'The current password is incorrect' } })));
        const fixture = create();
        const c = fixture.componentInstance;
        const snack = vi.spyOn(c['snackBar'], 'open');
        c.form.setValue({ currentPassword: 'wrong', newPassword: 'long-enough-1', confirmPassword: 'long-enough-1' });

        c.submit();

        expect(router.navigate).not.toHaveBeenCalled();
        expect(snack).toHaveBeenCalledWith('The current password is incorrect', 'Dismiss', expect.anything());
        expect(c.form.controls.currentPassword.value).toBe('');
        expect(c.loading).toBe(false);
    });
});
