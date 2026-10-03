import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { CorporateActionsService } from './corporate-actions.service';

describe('CorporateActionsService (C6: sign-off bound to the computed amounts)', () => {
  let service: CorporateActionsService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/corporate-actions`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [CorporateActionsService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CorporateActionsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('confirmSettlement sends the digest the operator reviewed, so re-computed amounts are refused instead of confirmed unseen', () => {
    service.confirmSettlement('ca-1', 'step-up-token', 'abc123').subscribe();

    const req = httpMock.expectOne(`${base}/ca-1/confirm-settlement`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ payoutDigest: 'abc123' });
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-token');
    req.flush({});
  });

  it('confirmSettlement without a digest keeps the legacy empty body', () => {
    service.confirmSettlement('ca-1', 'step-up-token').subscribe();

    const req = httpMock.expectOne(`${base}/ca-1/confirm-settlement`);
    expect(req.request.body).toEqual({});
    req.flush({});
  });
});
