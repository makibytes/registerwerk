import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RepoDeskService } from './repo-desk.service';
import { environment } from '../../../environments/environment';

describe('RepoDeskService (K3 contract)', () => {
  let service: RepoDeskService;
  let http: HttpTestingController;
  const base = `${environment.apiUrl}/repo-desk`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [RepoDeskService, provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(RepoDeskService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('accepts a quote with the server terms hash, not an empty body', () => {
    service.acceptQuote('r1', 'q1', 'abc123').subscribe();
    const req = http.expectOne(`${base}/rfqs/r1/quotes/q1/accept`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ termsHash: 'abc123' });
    req.flush({});
  });

  it('participation opt-in sends the listing choice; opt-out is a DELETE', () => {
    service.optIn(true).subscribe();
    const put = http.expectOne(`${base}/participation`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ listed: true });
    put.flush({});
    service.optOut().subscribe();
    expect(http.expectOne(`${base}/participation`).request.method).toBe('DELETE');
  });

  it('payer declarations and recipient confirmations use different endpoints for open and close legs', () => {
    service.declareLeg('t1', 'open', 'CASH', 'ref1').subscribe();
    http.expectOne(`${base}/trades/t1/open-settlement/CASH/declare`).flush({});
    service.declareLeg('t1', 'close', 'COLLATERAL', 'ref2').subscribe();
    http.expectOne(`${base}/trades/t1/close-settlement/COLLATERAL/declare`).flush({});
    service.confirmLeg('t1', 'close', 'CASH', 'ref3').subscribe();
    const confirm = http.expectOne(`${base}/trades/t1/close-settlement/CASH`);
    expect(confirm.request.body).toEqual({ reference: 'ref3' });
    confirm.flush({});
  });

  it('margin call carries the valuation snapshot; delivered and confirm replace the removed satisfy endpoint', () => {
    service.marginCall('t1', { amount: 10, dueAt: '2030-01-01T00:00:00Z', valuationReference: 'v1', valuationAmount: 100, note: null }).subscribe();
    const call = http.expectOne(`${base}/trades/t1/margin-call`);
    expect(call.request.body).toMatchObject({ valuationReference: 'v1', valuationAmount: 100 });
    call.flush({});
    service.marginDelivered('t1', 'tx1', null).subscribe();
    http.expectOne(`${base}/trades/t1/margin-call/delivered`).flush({});
    service.marginConfirm('t1', 'rcpt', null).subscribe();
    http.expectOne(`${base}/trades/t1/margin-call/confirm`).flush({});
    http.expectNone(`${base}/trades/t1/margin-call/satisfy`);
  });

  it('substitution decisions address the request id; default is notice then declaration; SFTR is a GET', () => {
    service.decideSubstitution('t1', 's1', true, null).subscribe();
    http.expectOne(`${base}/trades/t1/substitution/s1/decision`).flush({});
    service.confirmSubstitutionLeg('t1', 'ORIGINAL_OUT', 'r').subscribe();
    http.expectOne(`${base}/trades/t1/substitution/ORIGINAL_OUT`).flush({});
    service.defaultNotice('t1', null).subscribe();
    http.expectOne(`${base}/trades/t1/default-notice`).flush({});
    service.declareDefault('t1', 'n').subscribe();
    http.expectOne(`${base}/trades/t1/default`).flush({});
    service.sftrFields('t1').subscribe();
    expect(http.expectOne(`${base}/trades/t1/sftr-fields`).request.method).toBe('GET');
  });
});
