import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { OutboxService } from './outbox.service';

describe('OutboxService (P4B-4)', () => {
  let service: OutboxService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/admin/chains/c1/outbox`;
  const tokens = { stepUpToken: 'su', dualControlToken: 'dc' };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [OutboxService, provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(OutboxService);
    httpMock = TestBed.inject(HttpTestingController);
  });
  afterEach(() => httpMock.verify());

  it('listStuck() GETs the stuck queue of the chain', () => {
    service.listStuck('c1').subscribe();
    const req = httpMock.expectOne(`${base}/stuck`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('cancel() and reprice() POST the reason with step-up and second-approver headers', () => {
    service.cancel('c1', 'o1', 'stuck for two hours', tokens).subscribe();
    let req = httpMock.expectOne(`${base}/o1/cancel`);
    expect(req.request.body).toEqual({ reason: 'stuck for two hours' });
    expect(req.request.headers.get('Authorization')).toBe('Bearer su');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
    req.flush({});

    service.reprice('c1', 'o2', 'underpriced on chain', tokens).subscribe();
    req = httpMock.expectOne(`${base}/o2/reprice`);
    expect(req.request.body).toEqual({ reason: 'underpriced on chain' });
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
    req.flush({});
  });
});
