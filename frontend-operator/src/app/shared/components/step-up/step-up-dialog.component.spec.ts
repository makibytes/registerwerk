import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { StepUpDialogComponent, StepUpDialogData } from './step-up-dialog.component';
import { StepUpService } from '../../../core/api/step-up.service';
import { ApprovalQueueService } from '../../../core/api/approval-queue.service';

describe('StepUpDialogComponent', () => {
  const data: StepUpDialogData = {
    requireDualControl: true,
    reason: 'Mint',
    action: 'ISSUER_MINT',
    target: 'POST /api/v1/assets/a/deployments/d/issuer/mint',
    targetBody: { toAddress: '0xabc', amount: '10' },
  };
  let writeText: ReturnType<typeof vi.fn>;
  const close = vi.fn();
  const issueToken = vi.fn();
  const pendingView = {
    id: 'r1', requesterUserId: 'u1', requesterEmail: 'me@x', action: 'ISSUER_MINT', method: 'POST',
    path: '/api/v1/assets/a/deployments/d/issuer/mint', query: null, canonicalBody: '{"amount":"10"}', targetDigest: 'd',
    status: 'PENDING', approverUserId: null, approverEmail: null, createdAt: '2026-01-01T00:00:00Z',
    expiresAt: '2099-01-01T00:00:00Z', decidedAt: null, decisionNote: null, claimedAt: null,
  };
  const queue = { create: vi.fn(), get: vi.fn(), claim: vi.fn(), cancel: vi.fn() };

  beforeEach(() => {
    close.mockReset();
    issueToken.mockReset();
    Object.values(queue).forEach(fn => fn.mockReset());
    writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    TestBed.configureTestingModule({
      imports: [StepUpDialogComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
        { provide: StepUpService, useValue: { issueToken } },
        { provide: ApprovalQueueService, useValue: queue },
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

  describe('in-app approval queue', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    function open() {
      const fixture = TestBed.createComponent(StepUpDialogComponent);
      fixture.detectChanges();
      return fixture;
    }

    it('files the exact method, path and body of the pending request and cannot confirm while waiting', async () => {
      queue.create.mockReturnValue(of(pendingView));
      queue.get.mockReturnValue(of(pendingView));
      queue.cancel.mockReturnValue(of({ ...pendingView, status: 'CANCELLED' }));
      const c = open().componentInstance;
      c.totpCode = '123456';
      c.requestApproval();
      expect(queue.create).toHaveBeenCalledWith({
        action: 'ISSUER_MINT', method: 'POST', path: '/api/v1/assets/a/deployments/d/issuer/mint',
        body: { toAddress: '0xabc', amount: '10' },
      });
      expect(c.session.phase).toBe('waiting');
      expect(c.canConfirm).toBe(false);
      c.cancel();
      expect(queue.cancel).toHaveBeenCalledWith('r1');
      expect(close).toHaveBeenCalledWith(undefined);
    });

    it('on approval asks for a fresh code, then steps up, claims and returns the claimed token as the dual-control token', async () => {
      queue.create.mockReturnValue(of(pendingView));
      queue.get.mockReturnValue(of({ ...pendingView, status: 'APPROVED', approverEmail: 'boss@x' }));
      queue.claim.mockReturnValue(of({ requestId: 'r1', approvalToken: 'claimed-token', expiresAt: 'x', action: 'ISSUER_MINT', target: 't', headerName: 'X-Dual-Control-Token' }));
      issueToken.mockReturnValue(of({ stepUpToken: 'own-step-up' }));
      const c = open().componentInstance;
      c.totpCode = '111111'; // typed during the wait: stale once the approval arrives
      c.requestApproval();
      await vi.advanceTimersByTimeAsync(4000);
      expect(c.session.phase).toBe('approved');
      expect(c.totpCode).toBe('');
      expect(queue.claim).not.toHaveBeenCalled();

      c.totpCode = '222222';
      expect(c.canConfirm).toBe(true);
      c.submit();
      expect(issueToken).toHaveBeenCalledWith('222222', undefined, undefined, undefined);
      expect(queue.claim).toHaveBeenCalledWith('r1');
      expect(close).toHaveBeenCalledWith({ stepUpToken: 'own-step-up', dualControlToken: 'claimed-token' });
    });

    it('shows a rejection inline and offers to request again; confirming stays impossible', async () => {
      queue.create.mockReturnValue(of(pendingView));
      queue.get.mockReturnValue(of({ ...pendingView, status: 'REJECTED', approverEmail: 'boss@x', decisionNote: 'no way' }));
      const fixture = open();
      const c = fixture.componentInstance;
      c.requestApproval();
      await vi.advanceTimersByTimeAsync(4000);
      fixture.detectChanges();
      expect(c.session.phase).toBe('failed');
      expect(c.failureTitle).toBe('Rejected');
      c.totpCode = '123456';
      expect(c.canConfirm).toBe(false);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('no way');
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Request approval again');
    });

    it('a refused claim (double use) stays in the dialog with an inline error and does not close it', async () => {
      queue.create.mockReturnValue(of(pendingView));
      queue.get.mockReturnValue(of({ ...pendingView, status: 'APPROVED' }));
      queue.claim.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'already claimed' } })));
      issueToken.mockReturnValue(of({ stepUpToken: 'own-step-up' }));
      const c = open().componentInstance;
      c.requestApproval();
      await vi.advanceTimersByTimeAsync(4000);
      c.totpCode = '222222';
      c.submit();
      expect(close).not.toHaveBeenCalled();
      expect(c.session.phase).toBe('failed');
      expect(c.session.error).toBe('already claimed');
      expect(c.loading).toBe(false);
    });

    it('the pasted-token fallback still works without the queue', () => {
      issueToken.mockReturnValue(of({ stepUpToken: 'own-step-up' }));
      const c = open().componentInstance;
      c.totpCode = '123456';
      c.approverToken = ' pasted ';
      expect(c.canConfirm).toBe(true);
      c.submit();
      expect(queue.create).not.toHaveBeenCalled();
      expect(close).toHaveBeenCalledWith({ stepUpToken: 'own-step-up', dualControlToken: 'pasted' });
    });
  });
});
