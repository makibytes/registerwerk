import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { NEVER } from 'rxjs';
import { ChainService } from '../../core/api/chain.service';
import { OrgIdentityService } from '../../core/api/org-identity.service';
import { PermissionService } from '../../core/api/permission.service';
import { EcosystemTrustedIssuerView, PermissionGrantView } from '../../core/models';
import { stepUpDialogSpy } from '../../shared/components/step-up/step-up-test-helper';
import { PermissionDetailComponent } from './permission-detail/permission-detail.component';
import { PermissionListComponent } from './permission-list/permission-list.component';

/** Grants, revocations and trusted-issuer changes hand the approver the exact request the service then sends. */
describe('permission screens - dual-control targets', () => {
  const service = {
    grantToOrg: vi.fn(), revokeGrant: vi.fn(), addTrustedIssuer: vi.fn(), removeTrustedIssuer: vi.fn(),
  };

  function setup<T>(component: new () => T, providers: unknown[] = []) {
    const spy = stepUpDialogSpy();
    TestBed.configureTestingModule({
      imports: [component],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PermissionService, useValue: service },
        { provide: ChainService, useValue: {} },
        { provide: OrgIdentityService, useValue: {} },
        ...(providers as never[]),
      ],
    });
    TestBed.overrideProvider(MatSnackBar, { useValue: { open: vi.fn() } });
    TestBed.overrideProvider(MatDialog, { useValue: spy.dialog });
    return { instance: TestBed.createComponent(component).componentInstance, ...spy };
  }

  beforeEach(() => {
    vi.resetAllMocks();
    for (const m of Object.values(service)) m.mockReturnValue(NEVER);
  });

  it('granting a permission binds POST .../org-grants and the org registration id', () => {
    const route = { snapshot: { paramMap: { get: () => 'def-1' } } };
    const { instance, stepUps } = setup(PermissionDetailComponent, [{ provide: ActivatedRoute, useValue: route }]);
    const c = instance as unknown as { selectedOrgId: string; submitGrant(): void };
    c.selectedOrgId = 'org-1';
    c.submitGrant();
    expect(stepUps[0]).toMatchObject({
      requireDualControl: true, action: 'Permission grant to organization',
      target: 'POST /api/v1/permissions/def-1/org-grants', targetBody: { orgRegistrationId: 'org-1' },
    });
    expect(service.grantToOrg).toHaveBeenCalledWith('def-1', 'org-1', 'su', 'dc');
  });

  it('revoking a grant binds DELETE .../org-grants/{id} and no body', () => {
    const route = { snapshot: { paramMap: { get: () => 'def-1' } } };
    const { instance, stepUps } = setup(PermissionDetailComponent, [{ provide: ActivatedRoute, useValue: route }]);
    (instance as unknown as { revoke(g: PermissionGrantView): void }).revoke({ id: 'g1' } as PermissionGrantView);
    expect(stepUps[0]).toMatchObject({
      action: 'Permission grant revocation', target: 'DELETE /api/v1/permissions/org-grants/g1',
    });
    expect(stepUps[0].targetBody).toBeUndefined();
    expect(service.revokeGrant).toHaveBeenCalledWith('g1', 'su', 'dc');
  });

  it('registering a trusted issuer binds POST .../trusted-issuers and the exact body', () => {
    const { instance, stepUps } = setup(PermissionListComponent);
    const c = instance as unknown as {
      issuerChainId: string; issuerAddress: string; issuerTopics: string; submitIssuer(): void;
    };
    c.issuerChainId = 'chain-1';
    c.issuerAddress = ' 0xabc ';
    c.issuerTopics = '1, 2';
    c.submitIssuer();
    const body = { chainConfigId: 'chain-1', issuerAddress: '0xabc', claimTopics: [1, 2] };
    expect(stepUps[0]).toMatchObject({
      requireDualControl: true, action: 'Trusted claim issuer registration',
      target: 'POST /api/v1/permissions/trusted-issuers', targetBody: body,
    });
    expect(service.addTrustedIssuer).toHaveBeenCalledWith(body, 'su', 'dc');
  });

  it('removing a trusted issuer binds DELETE .../trusted-issuers/{id} and no body', () => {
    const { instance, stepUps } = setup(PermissionListComponent);
    (instance as unknown as { removeIssuer(i: EcosystemTrustedIssuerView): void })
      .removeIssuer({ id: 'i1' } as EcosystemTrustedIssuerView);
    expect(stepUps[0]).toMatchObject({
      action: 'Trusted claim issuer removal', target: 'DELETE /api/v1/permissions/trusted-issuers/i1',
    });
    expect(stepUps[0].targetBody).toBeUndefined();
    expect(service.removeTrustedIssuer).toHaveBeenCalledWith('i1', 'su', 'dc');
  });
});
