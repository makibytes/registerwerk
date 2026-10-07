import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ApprovalRequestSession, CreateApprovalRequest } from '@registerwerk/ui';
import { of, throwError } from 'rxjs';
import { ApprovalRequestBoxComponent } from './approval-request-box.component';

const request: CreateApprovalRequest = {
  action: 'ISSUER_MINT', method: 'POST', path: '/api/v1/assets/a/deployments/d/issuer/mint', body: { toAddress: '0xabc', amount: '1' },
};

function view(status: string, over: object = {}) {
  return {
    id: 'req-1', requesterUserId: 'u', requesterEmail: 'me@x', action: 'ISSUER_MINT', method: 'POST', path: request.path, query: null,
    canonicalBody: '{"amount":"1","toAddress":"0xabc"}', targetDigest: 'd', status, approverUserId: null, approverEmail: null,
    createdAt: '2026-01-01T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', decidedAt: null, decisionNote: null, claimedAt: null, ...over,
  };
}

describe('ApprovalRequestBoxComponent', () => {
  const queue = { create: vi.fn(), get: vi.fn(), claim: vi.fn(), cancel: vi.fn() };

  beforeEach(() => {
    vi.useFakeTimers();
    Object.values(queue).forEach(fn => fn.mockReset());
    queue.cancel.mockReturnValue(of(view('CANCELLED')));
    TestBed.configureTestingModule({ imports: [ApprovalRequestBoxComponent], providers: [provideZonelessChangeDetection()] });
  });
  afterEach(() => vi.useRealTimers());

  function open(req: CreateApprovalRequest | null = request) {
    const session = new ApprovalRequestSession(queue as never, () => fixture.changeDetectorRef.markForCheck(), { pollIntervalMs: 1000 });
    const fixture = TestBed.createComponent(ApprovalRequestBoxComponent);
    fixture.componentRef.setInput('session', session);
    fixture.componentRef.setInput('request', req);
    fixture.detectChanges();
    return { fixture, session, el: fixture.nativeElement as HTMLElement };
  }

  it('says that operator staff approve it and the user cannot, and shows the exact request', async () => {
    const { el } = open();
    expect(el.textContent).toContain('You cannot approve it yourself');
    expect(el.textContent).toContain('operator');
    expect(el.textContent).toContain('POST /api/v1/assets/a/deployments/d/issuer/mint');
    expect(el.textContent).toContain('"toAddress": "0xabc"');
  });

  it('cannot file an incomplete form', async () => {
    const { el } = open(null);
    expect(el.querySelector('button')?.disabled).toBe(true);
  });

  it('files the request, waits, and reports the approval', async () => {
    queue.create.mockReturnValue(of(view('PENDING')));
    queue.get.mockReturnValue(of(view('APPROVED', { approverEmail: 'op@x' })));
    const { fixture, session, el } = open();
    el.querySelector('button')!.click();
    expect(queue.create).toHaveBeenCalledWith(request);
    expect(session.phase).toBe('waiting');
    await vi.advanceTimersByTimeAsync(2000);
    fixture.detectChanges();
    expect(session.phase).toBe('approved');
    expect(el.textContent).toContain('Approved by the operator');
    session.destroy();
  });

  it('shows a rejection inline with the approver note and lets the user ask again', async () => {
    queue.create.mockReturnValue(of(view('PENDING')));
    queue.get.mockReturnValue(of(view('REJECTED', { approverEmail: 'op@x', decisionNote: 'amount too high' })));
    const { fixture, el } = open();
    el.querySelector('button')!.click();
    await vi.advanceTimersByTimeAsync(2000);
    fixture.detectChanges();
    expect(el.textContent).toContain('Rejected');
    expect(el.textContent).toContain('amount too high');
    expect(el.textContent).toContain('Request approval again');
  });

  it('shows a refused create (too many open requests) as an inline error', async () => {
    queue.create.mockReturnValue(throwError(() => ({ status: 409, error: { message: 'Too many open approval requests' } })));
    const { fixture, el } = open();
    el.querySelector('button')!.click();
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Too many open approval requests');
  });

  it('withdraws the approval when the form is edited after filing (it is bound to the filed request)', async () => {
    queue.create.mockReturnValue(of(view('PENDING')));
    queue.get.mockReturnValue(of(view('APPROVED')));
    const { fixture, session } = open();
    session.start(request);
    await vi.advanceTimersByTimeAsync(2000);
    expect(session.phase).toBe('approved');

    fixture.componentRef.setInput('request', { ...request, body: { toAddress: '0xabc', amount: '2' } });
    fixture.detectChanges();
    expect(queue.cancel).toHaveBeenCalledWith('req-1');
    expect(session.phase).toBe('idle');
  });
});
