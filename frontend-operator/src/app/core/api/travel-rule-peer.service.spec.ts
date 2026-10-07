import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TravelRulePeerService, peerPath } from './travel-rule-peer.service';
import { environment } from '../../../environments/environment';

describe('TravelRulePeerService', () => {
  let service: TravelRulePeerService;
  let http: HttpTestingController;
  const base = `${environment.apiUrl}/compliance/travel-rule/peers`;
  const tokens = { stepUpToken: 'su', dualControlToken: 'dc' };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(TravelRulePeerService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('enable() POSTs /enable with an empty body, step-up bearer and the second approver token', () => {
    service.enable('did:example:casp', tokens).subscribe();
    const req = http.expectOne(`${base}/did%3Aexample%3Acasp/enable`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    expect(req.request.headers.get('Authorization')).toBe('Bearer su');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('disable() DELETEs the peer', () => {
    service.disable('v1', tokens).subscribe();
    const req = http.expectOne(`${base}/v1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('register() posts exactly the approved body and returns the one-time key', () => {
    let key = '';
    service.register({ vaspId: 'v1', legalName: 'Peer AG' }, tokens).subscribe((c) => (key = c.hmacKey));
    const req = http.expectOne(base);
    expect(req.request.body).toEqual({ vaspId: 'v1', legalName: 'Peer AG' });
    req.flush({ peer: { vaspId: 'v1', legalName: 'Peer AG', lei: null, status: 'ACTIVE', createdAt: '2026-10-07T00:00:00Z' }, hmacKey: 'k3y' });
    expect(key).toBe('k3y');
  });

  it('the approval target path uses the same encoding as the request URL', () => {
    expect(peerPath('did:example:casp')).toBe('/api/v1/compliance/travel-rule/peers/did%3Aexample%3Acasp');
  });
});
