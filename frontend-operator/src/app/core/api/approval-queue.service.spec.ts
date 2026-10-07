import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { Observable, Subject, of, throwError } from 'rxjs';
import {
  ApprovalClaim, ApprovalFlowError, ApprovalFlowEvent, ApprovalQueueApi, ApprovalRequestSession, ApprovalRequestView,
  awaitApproval, claimApproval, formatCountdown, parseApprovalTarget, prettyCanonicalBody,
} from '@registerwerk/ui';
import { environment } from '../../../environments/environment';
import { ApprovalQueueService } from './approval-queue.service';

/**
 * The shared in-app approval queue (`@registerwerk/ui`): HTTP shapes through the operator's service, and the
 * request -> wait -> claim flow with a fake API. Both apps use the same code, so this is its only spec.
 */
function view(status: ApprovalRequestView['status'], over: Partial<ApprovalRequestView> = {}): ApprovalRequestView {
  return {
    id: 'r1', requesterUserId: 'u1', requesterEmail: 'a@x', action: 'KYC_APPROVE', method: 'POST', path: '/api/v1/kyc/1/approve',
    query: null, canonicalBody: '{"a":1}', targetDigest: 'd', status, approverUserId: null, approverEmail: null,
    createdAt: '2026-01-01T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', decidedAt: null, decisionNote: null, claimedAt: null,
    ...over,
  };
}

const claimOf: ApprovalClaim = {
  requestId: 'r1', approvalToken: 'tok', expiresAt: '2099-01-01T00:05:00Z', action: 'KYC_APPROVE',
  target: 'POST /api/v1/kyc/1/approve', headerName: 'X-Dual-Control-Token',
};

function httpError(status: number, message?: string): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: message ? { message } : null });
}

/** Fake API: `get` answers from a script, one entry per poll (the last repeats). */
function fakeApi(script: (ApprovalRequestView | HttpErrorResponse)[], over: Partial<ApprovalQueueApi> = {}) {
  let polls = 0;
  const api = {
    create: vi.fn(() => of(view('PENDING'))),
    get: vi.fn((): Observable<ApprovalRequestView> => {
      const next = script[Math.min(polls++, script.length - 1)];
      return next instanceof HttpErrorResponse ? throwError(() => next) : of(next);
    }),
    claim: vi.fn(() => of(claimOf)),
    cancel: vi.fn(() => of(view('CANCELLED'))),
    ...over,
  } satisfies ApprovalQueueApi;
  return api;
}

async function collect(source$: Observable<ApprovalFlowEvent>, advanceMs: number) {
  const events: ApprovalFlowEvent[] = [];
  let error: unknown;
  let done = false;
  source$.subscribe({ next: e => events.push(e), error: e => (error = e), complete: () => (done = true) });
  await vi.advanceTimersByTimeAsync(advanceMs);
  return { events, error, done };
}

describe('ApprovalQueueService (HTTP shapes)', () => {
  let service: ApprovalQueueService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/approvals`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ApprovalQueueService);
    httpMock = TestBed.inject(HttpTestingController);
  });
  afterEach(() => httpMock.verify());

  it('create() POSTs the exact method/path/query/body to /approvals', () => {
    service.create({ action: 'KYC_APPROVE', method: 'POST', path: '/api/v1/kyc/1/approve', query: 'x=1', body: { a: 1 } }).subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ action: 'KYC_APPROVE', method: 'POST', path: '/api/v1/kyc/1/approve', query: 'x=1', body: { a: 1 } });
    req.flush(view('PENDING'));
  });

  it('pending(), pendingCount() and mine() read the inbox endpoints', () => {
    service.pending(1, 5).subscribe();
    const pending = httpMock.expectOne(r => r.url === `${base}/pending`);
    expect(pending.request.params.get('page')).toBe('1');
    expect(pending.request.params.get('size')).toBe('5');
    pending.flush({ content: [], totalElements: 0, totalPages: 0, page: 1, size: 5 });
    service.pendingCount().subscribe();
    httpMock.expectOne(`${base}/pending/count`).flush({ count: 2 });
    service.mine().subscribe();
    httpMock.expectOne(r => r.url === `${base}/mine`).flush({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 });
  });

  it('approve() sends the approver code and note, reject() only a note, claim()/cancel() an empty body', () => {
    service.approve('r1', '123456', 'ok').subscribe();
    expect(httpMock.expectOne(`${base}/r1/approve`).request.body).toEqual({ code: '123456', note: 'ok' });
    service.reject('r1', '').subscribe();
    expect(httpMock.expectOne(`${base}/r1/reject`).request.body).toEqual({});
    service.claim('r1').subscribe();
    expect(httpMock.expectOne(`${base}/r1/claim`).request.method).toBe('POST');
    service.cancel('r1').subscribe();
    expect(httpMock.expectOne(`${base}/r1/cancel`).request.method).toBe('POST');
  });

  it('full flow over HTTP: create -> poll PENDING -> poll APPROVED -> claim', async () => {
    vi.useFakeTimers();
    try {
      const events: string[] = [];
      awaitApproval(service, { action: 'KYC_APPROVE', method: 'POST', path: '/api/v1/kyc/1/approve' }, { pollIntervalMs: 1000 })
        .subscribe(e => events.push(e.kind));
      httpMock.expectOne(base).flush(view('PENDING'));
      await vi.advanceTimersByTimeAsync(1000);
      httpMock.expectOne(`${base}/r1`).flush(view('PENDING'));
      await vi.advanceTimersByTimeAsync(1000);
      httpMock.expectOne(`${base}/r1`).flush(view('APPROVED', { approverEmail: 'b@x' }));
      expect(events).toEqual(['created', 'waiting', 'approved']);
      await vi.advanceTimersByTimeAsync(5000);
      httpMock.expectNone(`${base}/r1`); // polling stopped after the decision

      let token: string | undefined;
      claimApproval(service, 'r1').subscribe(c => (token = c.approvalToken));
      httpMock.expectOne(`${base}/r1/claim`).flush(claimOf);
      expect(token).toBe('tok');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('approval flow', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('emits created, waiting, approved and then stops polling; claim returns the bound token once', async () => {
    const api = fakeApi([view('PENDING'), view('APPROVED', { approverEmail: 'b@x' })]);
    const { events, error, done } = await collect(awaitApproval(api, { action: 'A', method: 'POST', path: '/api/v1/x' }, { pollIntervalMs: 1000 }), 10_000);
    expect(error).toBeUndefined();
    expect(done).toBe(true);
    expect(events.map(e => e.kind)).toEqual(['created', 'waiting', 'approved']);
    expect(api.get).toHaveBeenCalledTimes(2);
    expect(api.claim).not.toHaveBeenCalled(); // nothing is claimed while waiting

    let claim: ApprovalClaim | undefined;
    claimApproval(api, 'r1').subscribe(c => (claim = c));
    expect(claim).toEqual(claimOf);
    expect(api.claim).toHaveBeenCalledWith('r1');
  });

  it('a rejection surfaces who rejected it and why', async () => {
    const api = fakeApi([view('REJECTED', { approverEmail: 'b@x', decisionNote: 'wrong amount' })]);
    const { error } = await collect(awaitApproval(api, { action: 'A', method: 'POST', path: '/p' }, { pollIntervalMs: 1000 }), 2000);
    expect(error).toBeInstanceOf(ApprovalFlowError);
    expect((error as ApprovalFlowError).code).toBe('REJECTED');
    expect((error as ApprovalFlowError).message).toContain('b@x');
    expect((error as ApprovalFlowError).message).toContain('wrong amount');
  });

  it.each([['EXPIRED'], ['CANCELLED'], ['CLAIMED']] as const)('a %s request ends the wait with its own failure code', async (status) => {
    const api = fakeApi([view(status)]);
    const { error } = await collect(awaitApproval(api, { action: 'A', method: 'POST', path: '/p' }, { pollIntervalMs: 1000 }), 2000);
    expect((error as ApprovalFlowError).code).toBe(status === 'CLAIMED' ? 'ALREADY_CLAIMED' : status);
  });

  it('gives up with TIMEOUT when nobody decides within the timeout', async () => {
    const api = fakeApi([view('PENDING')]);
    const { error } = await collect(awaitApproval(api, { action: 'A', method: 'POST', path: '/p' }, { pollIntervalMs: 1000, timeoutMs: 3000 }), 10_000);
    expect((error as ApprovalFlowError).code).toBe('TIMEOUT');
  });

  it('rides out transient poll failures but not a 404 or a fourth consecutive failure', async () => {
    const ok = fakeApi([httpError(503), httpError(0), view('APPROVED')]);
    expect((await collect(awaitApproval(ok, { action: 'A', method: 'POST', path: '/p' }, { pollIntervalMs: 1000 }), 10_000)).events.at(-1)?.kind).toBe('approved');

    const gone = fakeApi([httpError(404)]);
    expect(((await collect(awaitApproval(gone, { action: 'A', method: 'POST', path: '/p' }, { pollIntervalMs: 1000 }), 3000)).error as HttpErrorResponse).status).toBe(404);

    const down = fakeApi([httpError(503)]);
    expect(((await collect(awaitApproval(down, { action: 'A', method: 'POST', path: '/p' }, { pollIntervalMs: 1000 }), 10_000)).error as HttpErrorResponse).status).toBe(503);
  });

  it('a create failure (400 not a four-eyes route / 409 too many open requests) passes through', async () => {
    const api = fakeApi([view('PENDING')], { create: vi.fn(() => throwError(() => httpError(409, 'Too many open requests'))) });
    const { error } = await collect(awaitApproval(api, { action: 'A', method: 'POST', path: '/p' }), 1000);
    expect((error as HttpErrorResponse).status).toBe(409);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('a second claim of the same approval is refused (409 -> ALREADY_CLAIMED), a lost eligibility (403) is CLAIM_REFUSED', () => {
    const claim = vi.fn()
      .mockReturnValueOnce(of(claimOf))
      .mockReturnValueOnce(throwError(() => httpError(409, 'already claimed')))
      .mockReturnValueOnce(throwError(() => httpError(403, 'approver no longer eligible')));
    const api = fakeApi([view('APPROVED')], { claim });
    let first: ApprovalClaim | undefined;
    claimApproval(api, 'r1').subscribe(c => (first = c));
    expect(first?.approvalToken).toBe('tok');
    let second: unknown;
    claimApproval(api, 'r1').subscribe({ error: e => (second = e) });
    expect((second as ApprovalFlowError).code).toBe('ALREADY_CLAIMED');
    expect((second as ApprovalFlowError).message).toBe('already claimed');
    let third: unknown;
    claimApproval(api, 'r1').subscribe({ error: e => (third = e) });
    expect((third as ApprovalFlowError).code).toBe('CLAIM_REFUSED');
  });
});

describe('ApprovalRequestSession', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('moves idle -> waiting -> approved, claims once and then reports a double claim as failed', async () => {
    const claim = vi.fn()
      .mockReturnValueOnce(of(claimOf))
      .mockReturnValueOnce(throwError(() => httpError(409)));
    const api = fakeApi([view('PENDING'), view('APPROVED', { approverEmail: 'b@x' })], { claim });
    const onChange = vi.fn();
    const session = new ApprovalRequestSession(api, onChange, { pollIntervalMs: 1000 });
    expect(session.phase).toBe('idle');
    session.start({ action: 'A', method: 'POST', path: '/p' });
    expect(session.phase).toBe('waiting');
    await vi.advanceTimersByTimeAsync(2000);
    expect(session.phase).toBe('approved');
    expect(session.view?.approverEmail).toBe('b@x');

    let token: string | undefined;
    session.claim().subscribe(c => (token = c.approvalToken));
    expect(token).toBe('tok');
    session.claim().subscribe({ error: () => undefined });
    expect(session.phase).toBe('failed');
    expect(session.failureCode).toBe('ALREADY_CLAIMED');
    expect(session.error).toContain('no longer be used');
    session.destroy();
  });

  it('shows a rejection as failed with the approver\'s note', async () => {
    const api = fakeApi([view('REJECTED', { approverEmail: 'b@x', decisionNote: 'no' })]);
    const session = new ApprovalRequestSession(api, vi.fn(), { pollIntervalMs: 1000 });
    session.start({ action: 'A', method: 'POST', path: '/p' });
    await vi.advanceTimersByTimeAsync(1000);
    expect(session.phase).toBe('failed');
    expect(session.failureCode).toBe('REJECTED');
    expect(session.error).toContain('no');
    session.destroy();
  });

  it('cancel() withdraws the open request and stops polling; destroy() stops without cancelling', async () => {
    const poll = new Subject<ApprovalRequestView>();
    const api = fakeApi([view('PENDING')], { get: vi.fn(() => poll) });
    const session = new ApprovalRequestSession(api, vi.fn(), { pollIntervalMs: 1000 });
    session.start({ action: 'A', method: 'POST', path: '/p' });
    await vi.advanceTimersByTimeAsync(1000);
    session.cancel();
    expect(api.cancel).toHaveBeenCalledWith('r1');
    expect(session.phase).toBe('idle');

    session.start({ action: 'A', method: 'POST', path: '/p' });
    session.destroy();
    await vi.advanceTimersByTimeAsync(5000);
    expect(api.cancel).toHaveBeenCalledTimes(1);
  });

  it('refuses to claim before the request is approved', () => {
    const api = fakeApi([view('PENDING')]);
    const session = new ApprovalRequestSession(api, vi.fn());
    let error: unknown;
    session.claim().subscribe({ error: e => (error = e) });
    expect(error).toBeInstanceOf(ApprovalFlowError);
    expect(api.claim).not.toHaveBeenCalled();
  });
});

describe('approval helpers', () => {
  it('parseApprovalTarget splits method, path and raw query', () => {
    expect(parseApprovalTarget('POST /api/v1/a/b?x=1&y=2')).toEqual({ method: 'POST', path: '/api/v1/a/b', query: 'x=1&y=2' });
    expect(parseApprovalTarget('DELETE /api/v1/a')).toEqual({ method: 'DELETE', path: '/api/v1/a' });
    expect(parseApprovalTarget('not a target')).toBeNull();
  });

  it('prettyCanonicalBody lays the canonical text out without parsing it (big numbers survive)', () => {
    expect(prettyCanonicalBody('{"a":1,"b":{"c":[1,2],"d":{}},"e":"x,y:{"}'))
      .toBe('{\n  "a": 1,\n  "b": {\n    "c": [\n      1,\n      2\n    ],\n    "d": {}\n  },\n  "e": "x,y:{"\n}');
    expect(prettyCanonicalBody('{"v":123456789012345678901234567890}')).toContain('123456789012345678901234567890');
    expect(prettyCanonicalBody('')).toBe('');
    expect(prettyCanonicalBody(null)).toBe('');
  });

  it('formatCountdown renders mm:ss and never goes negative', () => {
    expect(formatCountdown(125)).toBe('2:05');
    expect(formatCountdown(-4)).toBe('0:00');
  });
});
