import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ShellComponent } from './shell.component';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environments/environment';

describe('ShellComponent', () => {
  let authServiceSpy: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    // ShellComponent's template renders <app-sidebar>, which also injects AuthService
    // and calls hasRole() while computing its visible nav sections — stub it too.
    authServiceSpy = jasmine.createSpyObj('AuthService', ['logout', 'hasRole']);
    authServiceSpy.hasRole.and.returnValue(false);

    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });
  });

  it('delegates logout() to AuthService.logout()', () => {
    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();

    fixture.componentInstance.logout();

    expect(authServiceSpy.logout).toHaveBeenCalledTimes(1);
  });

  it('exposes isTestEnv from the environment configuration', () => {
    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.isTestEnv).toBe(environment.testEnvironment);
  });
});
