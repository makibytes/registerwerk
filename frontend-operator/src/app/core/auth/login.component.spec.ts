import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from './auth.service';

describe('LoginComponent', () => {
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let router: Router;

  function createComponent() {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['loginWithCredentials']);

    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });

    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  });

  it('builds an invalid, empty form initially', () => {
    const fixture = createComponent();
    expect(fixture.componentInstance.form.valid).toBeFalse();
    expect(fixture.componentInstance.form.value).toEqual({ email: '', password: '' });
  });

  it('rejects an email that fails the email-format validator', () => {
    const fixture = createComponent();
    fixture.componentInstance.form.setValue({ email: 'not-an-email', password: 'hunter2' });
    expect(fixture.componentInstance.form.valid).toBeFalse();
    expect(fixture.componentInstance.form.get('email')?.hasError('email')).toBeTrue();
  });

  it('requires a non-empty password', () => {
    const fixture = createComponent();
    fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: '' });
    expect(fixture.componentInstance.form.valid).toBeFalse();
    expect(fixture.componentInstance.form.get('password')?.hasError('required')).toBeTrue();
  });

  it('is valid once both a well-formed email and a password are present', () => {
    const fixture = createComponent();
    fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'hunter2' });
    expect(fixture.componentInstance.form.valid).toBeTrue();
  });

  it('submit() is a no-op when the form is invalid — it never calls the auth service', () => {
    const fixture = createComponent();
    fixture.componentInstance.submit();

    expect(authServiceSpy.loginWithCredentials).not.toHaveBeenCalled();
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('submit() logs in and navigates to /dashboard on success', () => {
    authServiceSpy.loginWithCredentials.and.returnValue(of(void 0));
    const fixture = createComponent();
    fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'hunter2' });

    fixture.componentInstance.submit();

    expect(authServiceSpy.loginWithCredentials).toHaveBeenCalledWith('admin@example.com', 'hunter2');
    expect(fixture.componentInstance.loading).toBeFalse();
    expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
  });

  it('submit() sets loading synchronously before the auth call resolves', () => {
    // never-resolving observable so we can observe the interim state
    authServiceSpy.loginWithCredentials.and.returnValue({ subscribe: () => ({ unsubscribe: () => undefined }) } as never);
    const fixture = createComponent();
    fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'hunter2' });

    fixture.componentInstance.submit();

    expect(fixture.componentInstance.loading).toBeTrue();
  });

  it('submit() clears loading and does not navigate when the auth call fails', () => {
    authServiceSpy.loginWithCredentials.and.returnValue(throwError(() => ({ error: { message: 'Invalid credentials' } })));
    const fixture = createComponent();
    fixture.componentInstance.form.setValue({ email: 'admin@example.com', password: 'wrong' });

    fixture.componentInstance.submit();

    expect(fixture.componentInstance.loading).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
