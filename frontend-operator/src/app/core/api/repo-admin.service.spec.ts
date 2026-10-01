import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../environments/environment';
import { RepoAdminService, allowedDisputeResolutions } from './repo-admin.service';

describe('RepoAdminService', () => {
  let service: RepoAdminService;
  let http: HttpTestingController;
  const base = `${environment.apiUrl}/admin/repo`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [RepoAdminService, provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(RepoAdminService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('lists disputes', () => {
    service.listDisputes().subscribe();
    const req = http.expectOne(`${base}/disputes`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('resolve sends legal basis with step-up and dual-control tokens', () => {
    service.resolve('t1', { resolution: 'RESUME', legalBasis: 'parties agreed, ticket 42', note: null },
      { stepUpToken: 'su', dualControlToken: 'dc' }).subscribe();
    const req = http.expectOne(`${base}/disputes/t1/resolve`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ resolution: 'RESUME', legalBasis: 'parties agreed, ticket 42', note: null });
    expect(req.request.headers.get('Authorization')).toBe('Bearer su');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
    req.flush({});
  });

  it('offers CLOSE only from closing and CANCEL only from opening', () => {
    expect(allowedDisputeResolutions('OPEN')).toEqual(['RESUME']);
    expect(allowedDisputeResolutions('PENDING_CLOSE')).toEqual(['RESUME', 'CLOSE']);
    expect(allowedDisputeResolutions('PENDING_OPEN_SETTLEMENT')).toEqual(['RESUME', 'CANCEL']);
  });
});
