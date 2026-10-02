import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { StepUpDialogComponent, StepUpDialogData } from './step-up-dialog.component';
import { StepUpService } from '../../../core/api/step-up.service';

describe('StepUpDialogComponent', () => {
  const data: StepUpDialogData = {
    requireDualControl: true,
    reason: 'Mint',
    action: 'ISSUER_MINT',
    target: 'POST /api/v1/assets/a/deployments/d/issuer/mint',
    targetBody: { toAddress: '0xabc', amount: '10' },
  };
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    TestBed.configureTestingModule({
      imports: [StepUpDialogComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
        { provide: StepUpService, useValue: { issueToken: vi.fn() } },
      ],
    });
  });

  it('"Copy approval request" copies the exact action, target and body for the approver', () => {
    const fixture = TestBed.createComponent(StepUpDialogComponent);
    fixture.componentInstance.copyApprovalRequest();
    const copied = JSON.parse(writeText.mock.calls[0][0] as string);
    expect(copied).toEqual({ action: data.action, target: data.target, targetBody: data.targetBody });
  });

  it('never offers to mint the approver token inside the initiator\'s own session', async () => {
    const fixture = TestBed.createComponent(StepUpDialogComponent);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Generate approver token');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('their own signed-in');
  });

  it('announces a verification error and links it to the TOTP input', async () => {
    const fixture = TestBed.createComponent(StepUpDialogComponent);
    fixture.componentInstance.errorMessage = 'Step-up verification failed.';
    fixture.changeDetectorRef.markForCheck();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('#step-up-error')?.getAttribute('role')).toBe('alert');
    expect(el.querySelector('input[inputmode="numeric"]')?.getAttribute('aria-describedby')).toBe('step-up-error');
  });
});
