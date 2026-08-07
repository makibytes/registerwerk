import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HandoffComponent } from './handoff.component';
import { AuthService } from '../../core/auth/auth.service';

describe('HandoffComponent', () => {
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;

  function createComponent() {
    const fixture = TestBed.createComponent(HandoffComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', [
      'supportsImpersonation',
      'enterImpersonation',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

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
    authService.supportsImpersonation.and.returnValue(false);
    const replaceStateSpy = spyOn(history, 'replaceState').and.callThrough();

    const fixture = createComponent();

    expect(fixture.componentInstance.unsupported).toBe(true);
    expect(authService.enterImpersonation).not.toHaveBeenCalled();
    expect(replaceStateSpy).toHaveBeenCalled();
  });

  it('exchanges the token via AuthService and navigates to /dashboard on success', () => {
    window.location.hash = '#token=impersonation-tok&entityId=ent-1&entityName=Acme%20GmbH';
    authService.supportsImpersonation.and.returnValue(true);
    authService.enterImpersonation.and.returnValue(of(void 0));

    const fixture = createComponent();

    expect(fixture.componentInstance.unsupported).toBe(false);
    expect(authService.enterImpersonation).toHaveBeenCalledWith('impersonation-tok', 'ent-1', 'Acme GmbH');
    expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
  });

  it('navigates to /login when the token exchange fails', () => {
    window.location.hash = '#token=bad-tok&entityId=ent-1&entityName=Acme';
    authService.supportsImpersonation.and.returnValue(true);
    authService.enterImpersonation.and.returnValue(throwError(() => new Error('exchange failed')));

    createComponent();

    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('navigates straight to /login when the fragment carries no token', () => {
    window.location.hash = '';
    authService.supportsImpersonation.and.returnValue(true);

    createComponent();

    expect(authService.enterImpersonation).not.toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
