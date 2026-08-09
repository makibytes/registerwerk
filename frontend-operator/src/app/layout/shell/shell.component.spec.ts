import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ShellComponent } from './shell.component';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environments/environment';

describe('ShellComponent', () => {
    let authServiceSpy: MockedObject<Pick<AuthService, 'logout' | 'hasRole'>>;

    beforeEach(() => {
        // ShellComponent's template renders <app-sidebar>, which also injects AuthService
        // and calls hasRole() while computing its visible nav sections — stub it too.
        authServiceSpy = {
            logout: vi.fn().mockName("AuthService.logout"),
            hasRole: vi.fn().mockName("AuthService.hasRole")
        };
        authServiceSpy.hasRole.mockReturnValue(false);

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
