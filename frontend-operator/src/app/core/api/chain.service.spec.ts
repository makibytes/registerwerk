import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { ChainService } from './chain.service';
import { DualControlTokens } from './dual-control-headers';

describe('ChainService RPC node governance (P4C-1)', () => {
  let service: ChainService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/admin/chains`;
  const tokens: DualControlTokens = { stepUpToken: 'su', dualControlToken: 'dc' };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [ChainService, provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ChainService);
    httpMock = TestBed.inject(HttpTestingController);
  });
  afterEach(() => httpMock.verify());

  function expectDualControl(req: { request: { headers: { get(n: string): string | null } } }): void {
    expect(req.request.headers.get('Authorization')).toBe('Bearer su');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
  }

  it('every node mutation carries step-up + second approver headers', () => {
    service.addNode('c1', { url: 'https://rpc.example' }, tokens).subscribe();
    let req = httpMock.expectOne(`${base}/c1/nodes`);
    expect(req.request.method).toBe('POST');
    expectDualControl(req);
    req.flush({});

    service.updateNode('c1', 'n1', { url: 'https://rpc2.example' }, tokens).subscribe();
    req = httpMock.expectOne(`${base}/c1/nodes/n1`);
    expect(req.request.method).toBe('PUT');
    expectDualControl(req);
    req.flush({});

    service.enableNode('c1', 'n1', tokens).subscribe();
    req = httpMock.expectOne(`${base}/c1/nodes/n1/enable`);
    expectDualControl(req);
    req.flush(null);

    service.disableNode('c1', 'n1', tokens).subscribe();
    req = httpMock.expectOne(`${base}/c1/nodes/n1/disable`);
    expectDualControl(req);
    req.flush(null);

    service.setExclusive('c1', 'n1', true, tokens).subscribe();
    req = httpMock.expectOne(`${base}/c1/nodes/n1/exclusive?value=true`);
    expectDualControl(req);
    req.flush(null);

    service.deleteNode('c1', 'n1', tokens).subscribe();
    req = httpMock.expectOne(`${base}/c1/nodes/n1`);
    expect(req.request.method).toBe('DELETE');
    expectDualControl(req);
    req.flush(null);
  });

  it('resetGenesisPin() POSTs to the genesis-pin/reset endpoint with dual control', () => {
    service.resetGenesisPin('c1', tokens).subscribe();
    const req = httpMock.expectOne(`${base}/c1/nodes/genesis-pin/reset`);
    expect(req.request.method).toBe('POST');
    expectDualControl(req);
    req.flush(null);
  });
});
