import { beforeEach, describe, expect, it, type MockedObject, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { roleGuard } from '../../core/auth/auth.guard';
import { AuthService } from '../../core/auth/auth.service';
import { COMPLIANCE_ROUTES, KYC_REVIEW_ROLES } from './compliance.routes';

describe('KYC queue / review routes (T8-03)', () => {
  let auth: MockedObject<Pick<AuthService, 'hasRole'>>;
  let dashboard: UrlTree;

  const kycRoutes = COMPLIANCE_ROUTES.filter((r) => r.path === 'kyc' || r.path === 'kyc/:id');

  function canActivate(route: (typeof kycRoutes)[number], roles: string[]): boolean | UrlTree {
    auth.hasRole.mockImplementation((role: string) => roles.includes(role));
    return TestBed.runInInjectionContext(
      () => roleGuard({ data: route.data } as never, {} as never) as boolean | UrlTree,
    );
  }

  beforeEach(() => {
    auth = { hasRole: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: auth }] });
    dashboard = TestBed.inject(Router).createUrlTree(['/dashboard']);
    vi.spyOn(TestBed.inject(Router), 'createUrlTree').mockReturnValue(dashboard);
  });

  it('declares the queue and the review, both behind the role guard', () => {
    expect(kycRoutes.map((r) => r.path)).toEqual(['kyc', 'kyc/:id']);
    for (const route of kycRoutes) {
      expect(route.canActivate).toContain(roleGuard);
      expect(route.data?.['roles']).toEqual([...KYC_REVIEW_ROLES]);
    }
  });

  it('admits registry administrators and compliance officers', () => {
    for (const route of kycRoutes) {
      expect(canActivate(route, ['COMPLIANCE_OFFICER'])).toBe(true);
      expect(canActivate(route, ['REGISTRY_ADMIN'])).toBe(true);
    }
  });

  it('sends every other operator role to the dashboard', () => {
    for (const route of kycRoutes) {
      for (const role of ['AUDIT', 'RELATIONSHIP_MANAGER', 'SUPPORT_AGENT', 'TRADER']) {
        expect(canActivate(route, [role])).toBe(dashboard);
      }
    }
  });
});
