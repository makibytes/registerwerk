import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from './auth.service';

describe('LoginComponent', () => {
    let authServiceSpy: MockedObject<Pick<AuthService, 'loginWithCredentials'>>;
    let router: Router;

    function createComponent() {
        const fixture = TestBed.createComponent(LoginComponent);
        fixture.detectChanges();
        return fixture;
    }

    beforeEach(() => {
        authServiceSpy = {
            loginWithCredentials: vi.fn().mockName("AuthService.loginWithCredentials")
        };

        TestBed.configureTestingModule({
            imports: [LoginComponent],
            providers: [
                provideRouter([]),
                { provide: AuthService, useValue: authServiceSpy },
            ],
        });

        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate').mockResolvedValue(true);
    });

    it('builds an invalid, empty form initially', () => {
        const fixture = createComponent();
        expect(fixture.componentInstance.form.valid).toBe(false);
        expect(fixture.componentInstance.form.value).toEqual({ email: '', password: '' });
    });

    it('rejects an email that fails the email-format validator', () => {
        const fixture = createComponent();
        fixture.componentInstance.form.setValue({ email: 'not-an-email', password: 'hunter2' });
        expect(fixture.componentInstance.form.valid).toBe(false);
        expect(fixture.componentInstance.form.get('email')?.hasError('email')).toBe(true);
    });

    it('requires a non-empty password', () => {
        const fixture = createComponent();
        fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: '' });
        expect(fixture.componentInstance.form.valid).toBe(false);
        expect(fixture.componentInstance.form.get('password')?.hasError('required')).toBe(true);
    });

    it('is valid once both a well-formed email and a password are present', () => {
        const fixture = createComponent();
        fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'hunter2' });
        expect(fixture.componentInstance.form.valid).toBe(true);
    });

    it('submit() is a no-op when the form is invalid — it never calls the auth service', () => {
        const fixture = createComponent();
        fixture.componentInstance.submit();

        expect(authServiceSpy.loginWithCredentials).not.toHaveBeenCalled();
        expect(fixture.componentInstance.loading).toBe(false);
    });

    it('submit() logs in and navigates to /dashboard on success', () => {
        authServiceSpy.loginWithCredentials.mockReturnValue(of(void 0));
        const fixture = createComponent();
        fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'hunter2' });

        fixture.componentInstance.submit();

        expect(authServiceSpy.loginWithCredentials).toHaveBeenCalledWith('admin@example.com', 'hunter2');
        expect(fixture.componentInstance.loading).toBe(false);
        expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
    });

    it('submit() sets loading synchronously before the auth call resolves', () => {
        // never-resolving observable so we can observe the interim state
        authServiceSpy.loginWithCredentials.mockReturnValue({ subscribe: () => ({ unsubscribe: () => undefined }) } as never);
        const fixture = createComponent();
        fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'hunter2' });

        fixture.componentInstance.submit();

        expect(fixture.componentInstance.loading).toBe(true);
    });

    it('submit() clears loading and does not navigate when the auth call fails', () => {
        authServiceSpy.loginWithCredentials.mockReturnValue(throwError(() => ({ error: { message: 'Invalid credentials' } })));
        const fixture = createComponent();
        fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'wrong' });

        fixture.componentInstance.submit();

        expect(fixture.componentInstance.loading).toBe(false);
        expect(router.navigate).not.toHaveBeenCalled();
    });

    it('submit() tells the user how long to wait when the login throttle answers 429', () => {
        authServiceSpy.loginWithCredentials.mockReturnValue(throwError(() => ({
            status: 429, headers: { get: (n: string) => (n === 'Retry-After' ? '12' : null) },
            error: { message: 'Too many login attempts. Try again later.' },
        })));
        const fixture = createComponent();
        const snack = vi.spyOn(fixture.componentInstance['snackBar'], 'open');
        fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'wrong' });

        fixture.componentInstance.submit();

        expect(snack).toHaveBeenCalledWith('Too many sign-in attempts. Please try again in 12 seconds.', 'Dismiss', { duration: 5000 });
        expect(fixture.componentInstance.loading).toBe(false);
    });
});
