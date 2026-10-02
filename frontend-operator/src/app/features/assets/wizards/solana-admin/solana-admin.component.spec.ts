import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { SolanaAdminComponent } from './solana-admin.component';
import { SolanaAdminService } from '../../../../core/api/solana-admin.service';
import { TransactionService } from '../../../../core/api/transaction.service';

describe('SolanaAdminComponent error handling (8B-04)', () => {
  let service: Record<string, ReturnType<typeof vi.fn>>;
  let snackBar: { open: ReturnType<typeof vi.fn> };

  function create() {
    const fixture = TestBed.createComponent(SolanaAdminComponent);
    fixture.componentInstance.assetId = 'a1';
    fixture.componentInstance.deploymentId = 'd1';
    // The component imports MatDialogModule, so its MatDialog lives in the component injector.
    const dialog = fixture.debugElement.injector.get(MatDialog);
    vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of({ stepUpToken: 'su', dualControlToken: 'dc' }) } as never);
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = {
      freeze: vi.fn(), thaw: vi.fn(), forcedTransfer: vi.fn(), forceBurn: vi.fn(),
    };
    snackBar = { open: vi.fn() };
    TestBed.configureTestingModule({
      imports: [SolanaAdminComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: SolanaAdminService, useValue: service },
        { provide: TransactionService, useValue: { track: vi.fn() } },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
  });

  it('freeze: a 409 shows the server message', () => {
    service['freeze'].mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Account already frozen' } })));
    const c = create();
    c.freezeAccount = 'ACC';
    c.submitFreeze();
    expect(snackBar.open.mock.calls[0][0]).toBe('Account already frozen');
  });

  it('forced transfer / force burn: the message includes that the approver token was consumed', () => {
    service['forcedTransfer'].mockReturnValue(throwError(() => ({ status: 400, error: { message: 'Insufficient balance' } })));
    service['forceBurn'].mockReturnValue(throwError(() => ({ status: 403, error: {} })));
    const c = create();
    c.submitForcedTransfer();
    expect(snackBar.open.mock.calls[0][0]).toContain('Insufficient balance');
    expect(snackBar.open.mock.calls[0][0]).toContain('approver token was consumed');
    c.submitForceBurn();
    expect(snackBar.open.mock.calls[1][0]).toContain('Force burn failed.');
    expect(snackBar.open.mock.calls[1][0]).toContain('approver token was consumed');
  });
});
