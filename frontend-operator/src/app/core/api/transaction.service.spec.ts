import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { TransactionService } from './transaction.service';
import { environment } from '../../../environments/environment';

describe('TransactionService.track (8B-10)', () => {
  let service: TransactionService;
  let http: HttpTestingController;
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let dismissed: Subject<void>[];
  const url = `${environment.apiUrl}/transactions/tx-1`;

  function pollOnce(response: { status: number } | { body: object }) {
    vi.advanceTimersByTime(4_000);
    const req = http.expectOne(url);
    if ('status' in response) req.flush('boom', { status: response.status, statusText: 'err' });
    else req.flush(response.body);
  }

  const messages = () => snackBar.open.mock.calls.map(c => c[0] as string);

  beforeEach(() => {
    vi.useFakeTimers();
    dismissed = [];
    snackBar = {
      open: vi.fn().mockImplementation(() => {
        const d = new Subject<void>();
        dismissed.push(d);
        return { dismiss: () => { d.next(); d.complete(); }, afterDismissed: () => d.asObservable(), instance: {} };
      }),
    };
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: MatSnackBar, useValue: snackBar }],
    });
    service = TestBed.inject(TransactionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { vi.useRealTimers(); });

  it('one failed poll followed by SUCCESS still ends in the success toast', () => {
    service.track('tx-1', 'Mint');
    pollOnce({ status: 503 });
    pollOnce({ body: { status: 'SUCCESS', blockNumber: 7 } });
    expect(messages().some(m => m.includes('confirmed (block 7)'))).toBe(true);
    expect(messages().some(m => m.includes('status unknown'))).toBe(false);
  });

  it('five consecutive failures end with a persistent "status unknown" toast', () => {
    service.track('tx-1', 'Mint');
    for (let i = 0; i < 5; i++) pollOnce({ status: 500 });
    const last = snackBar.open.mock.calls.at(-1)!;
    expect(last[0]).toContain('status unknown');
    expect(last[0]).toContain('before resubmitting');
    expect(last[2]).toMatchObject({ duration: 0 });
    vi.advanceTimersByTime(40_000);
    http.expectNone(url);
  });

  it('a success between failures resets the counter', () => {
    service.track('tx-1', 'Mint');
    for (let i = 0; i < 4; i++) pollOnce({ status: 500 });
    pollOnce({ body: { status: 'PENDING' } });
    for (let i = 0; i < 4; i++) pollOnce({ status: 500 });
    expect(messages().some(m => m.includes('status unknown'))).toBe(false);
  });

  it('TIMEOUT still shows the no-resubmit warning', () => {
    service.track('tx-1', 'Mint');
    pollOnce({ body: { status: 'TIMEOUT' } });
    expect(messages().some(m => m.includes('it may still execute'))).toBe(true);
  });

  it('re-opens the pending toast when another snackbar displaced it', () => {
    service.track('tx-1', 'Mint');
    dismissed[0].next(); // e.g. a "submitted" toast replaced it
    pollOnce({ body: { status: 'PENDING' } });
    expect(messages().filter(m => m.includes('submitting')).length).toBe(2);
    pollOnce({ body: { status: 'SUCCESS', blockNumber: 1 } });
  });
});
