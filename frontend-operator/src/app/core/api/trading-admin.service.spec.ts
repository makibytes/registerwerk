import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TradingAdminService } from './trading-admin.service';
import { environment } from '../../../environments/environment';

describe('TradingAdminService', () => {
  let service: TradingAdminService;
  let http: HttpTestingController;
  const base = `${environment.apiUrl}/admin/trading`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(TradingAdminService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('resolve() posts the legal basis with the step-up bearer and the second approver token', () => {
    service.resolve('t1', 'force-settle', { legalBasis: 'Bank statement 2026-03-02', note: null },
      { stepUpToken: 'su', dualControlToken: 'dc' }).subscribe();
    const req = http.expectOne(`${base}/unresolved/t1/force-settle`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Authorization')).toBe('Bearer su');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
    expect(req.request.body).toEqual({ legalBasis: 'Bank statement 2026-03-02', note: null });
    req.flush({});
  });

  it('listUnresolved() and backlog() GET the queue endpoints', () => {
    service.listUnresolved().subscribe();
    http.expectOne(`${base}/unresolved`).flush([]);
    service.backlog().subscribe();
    http.expectOne(`${base}/timeout-backlog`).flush({});
  });

  it('orderHistoryCsv() sends an ISO window as query parameters', () => {
    service.orderHistoryCsv('2026-03-01', '2026-03-31').subscribe();
    const req = http.expectOne((r) => r.url === `${base}/order-history`);
    expect(req.request.params.get('from')).toBe('2026-03-01T00:00:00.000Z');
    expect(req.request.params.get('to')).toBe('2026-03-31T23:59:59.000Z');
    req.flush(new Blob());
  });

  it('listHistoricFailed() reads the review-only list', () => {
    let out: unknown[] = [];
    service.listHistoricFailed().subscribe((r) => (out = r));
    http.expectOne(`${base}/historic-failed`).flush([{ trade: { id: 't1' }, notes: [] }]);
    expect(out.length).toBe(1);
  });
});
