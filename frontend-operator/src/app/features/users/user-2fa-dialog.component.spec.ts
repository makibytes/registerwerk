import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NEVER } from 'rxjs';
import { AdminUserService } from '../../core/api/admin-user.service';
import { stepUpDialogSpy } from '../../shared/components/step-up/step-up-test-helper';
import { User2faDialogComponent } from './user-2fa-dialog.component';

/** The two 4-eyes Entra actions hand the approver the exact request the service then sends. */
describe('User2faDialogComponent - dual-control targets', () => {
  const service = { resetEntraMfa: vi.fn(), issueTemporaryAccessPass: vi.fn(), getEntraMethods: vi.fn() };

  function create() {
    const spy = stepUpDialogSpy();
    TestBed.configureTestingModule({
      imports: [User2faDialogComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AdminUserService, useValue: service },
        { provide: MAT_DIALOG_DATA, useValue: { id: 'u1' } },
      ],
    });
    TestBed.overrideProvider(MatSnackBar, { useValue: { open: vi.fn() } });
    TestBed.overrideProvider(MatDialog, { useValue: spy.dialog });
    const component = TestBed.createComponent(User2faDialogComponent).componentInstance as unknown as {
      resetAll(): void; issueTap(): void;
    };
    return { component, ...spy };
  }

  beforeEach(() => {
    vi.resetAllMocks();
    service.resetEntraMfa.mockReturnValue(NEVER);
    service.issueTemporaryAccessPass.mockReturnValue(NEVER);
  });

  it('resetting all methods binds POST .../entra/methods/reset with the empty body', () => {
    const { component, stepUps } = create();
    component.resetAll();
    expect(stepUps[0]).toMatchObject({
      requireDualControl: true, action: 'ENTRA_MFA_RESET',
      target: 'POST /api/v1/admin/users/u1/entra/methods/reset', targetBody: {},
    });
    expect(service.resetEntraMfa).toHaveBeenCalledWith('u1', 'su', 'dc');
  });

  it('issuing a Temporary Access Pass binds POST .../entra/temporary-access-pass and the exact request', () => {
    const { component, stepUps } = create();
    component.issueTap();
    const body = { lifetimeMinutes: 60, usableOnce: true };
    expect(stepUps[0]).toMatchObject({
      requireDualControl: true, action: 'ENTRA_TEMPORARY_ACCESS_PASS',
      target: 'POST /api/v1/admin/users/u1/entra/temporary-access-pass', targetBody: body,
    });
    expect(service.issueTemporaryAccessPass).toHaveBeenCalledWith('u1', body, 'su', 'dc');
  });
});
