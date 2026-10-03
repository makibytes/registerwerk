import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { CorporateActionsService } from './corporate-actions.service';

describe('CorporateActionsService (C6: attestation bound to the computed amounts)', () => {
  let service: CorporateActionsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [CorporateActionsService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CorporateActionsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('attestSettlement sends the digest of the amounts the issuer was looking at', () => {
    service.attestSettlement('asset-1', 'ca-1', 'SEPA-REF-1', 'abc123').subscribe();

    const req = httpMock.expectOne(r => r.url.endsWith('/assets/asset-1/corporate-actions/ca-1/attest-settlement'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ attestationReference: 'SEPA-REF-1', acknowledged: true, payoutDigest: 'abc123' });
    req.flush({});
  });

  it('attestSettlement without a digest (older rows) sends null, never undefined', () => {
    service.attestSettlement('asset-1', 'ca-1', 'SEPA-REF-1').subscribe();

    const req = httpMock.expectOne(r => r.url.endsWith('/attest-settlement'));
    expect(req.request.body.payoutDigest).toBeNull();
    req.flush({});
  });
});
