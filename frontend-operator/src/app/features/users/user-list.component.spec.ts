import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NEVER, of } from 'rxjs';
import { AdminUserService, OperatorUser } from '../../core/api/admin-user.service';
import { UserListComponent } from './user-list.component';

/**
 * C3: switching off a privileged colleague is a four-eyes action, so the dialog asks for a second approver and
 * binds the request body the service then sends.
 */
describe('UserListComponent - disabling a privileged account', () => {
  let openedWith: Record<string, unknown> | undefined;
  let service: { loadUsers?: unknown; list: ReturnType<typeof vi.fn>; disableUser: ReturnType<typeof vi.fn> };

  const user = (over: Partial<OperatorUser>): OperatorUser => ({
    id: 'u1', email: 'a@x.de', name: 'A', roles: ['REGISTRY_ADMIN'], entityId: null, enabled: true,
    ...over,
  } as OperatorUser);

  beforeEach(() => {
    openedWith = undefined;
    service = {
      list: vi.fn().mockReturnValue(NEVER),
      disableUser: vi.fn().mockReturnValue(NEVER),
    };
    TestBed.configureTestingModule({
      imports: [UserListComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AdminUserService, useValue: service },
      ],
    });
    // The component imports MatDialogModule/MatSnackBarModule, which provide the real services at its own level.
    TestBed.overrideProvider(MatSnackBar, { useValue: { open: vi.fn() } });
    TestBed.overrideProvider(MatDialog, {
      useValue: {
        open: (_c: unknown, config: { data: Record<string, unknown> }) => {
          openedWith = config.data;
          return { afterClosed: () => of(undefined) };
        },
      },
    });
    vi.spyOn(globalThis, 'prompt').mockReturnValue('left the company');
  });

  it('asks for a second approver and binds the disable body for a REGISTRY_ADMIN', () => {
    const c = TestBed.createComponent(UserListComponent).componentInstance;
    c.toggleEnabled(user({}));
    expect(openedWith).toMatchObject({
      requireDualControl: true,
      dualControlOptional: true,
      action: 'OPERATOR_USER_DISABLE',
      target: 'POST /api/v1/admin/users/u1/disable',
      targetBody: { reason: 'left the company' },
    });
  });

  it('an ordinary company user still needs only the caller\'s own step-up', () => {
    const c = TestBed.createComponent(UserListComponent).componentInstance;
    c.toggleEnabled(user({ roles: ['TRADER'], entityId: 'e1' }));
    expect(openedWith).toMatchObject({ requireDualControl: false, action: 'OPERATOR_USER_DISABLE' });
  });

  it('enabling binds the empty body the service sends', () => {
    const c = TestBed.createComponent(UserListComponent).componentInstance;
    c.toggleEnabled(user({ enabled: false }));
    expect(openedWith).toMatchObject({ action: 'OPERATOR_USER_ENABLE', targetBody: {} });
  });
});
