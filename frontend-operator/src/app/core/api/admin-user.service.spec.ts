import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminUserService, isGatedOperatorAccount } from './admin-user.service';
import { environment } from '../../../environments/environment';

describe('AdminUserService - production mode flag and SUPPORT_AGENT (T6-05)', () => {
  let service: AdminUserService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AdminUserService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads productionMode from the public auth config', () => {
    let result: boolean | undefined;
    service.isProductionMode().subscribe((v) => (result = v));
    http.expectOne(`${environment.apiUrl}/public/auth/config`).flush({ mode: 'LOCAL', productionMode: true });
    expect(result).toBe(true);
  });

  it('treats an unreachable config as production (act-on-behalf stays hidden)', () => {
    let result: boolean | undefined;
    service.isProductionMode().subscribe((v) => (result = v));
    http.expectOne(`${environment.apiUrl}/public/auth/config`).flush('x', { status: 503, statusText: 'Unavailable' });
    expect(result).toBe(true);
  });

  it('SUPPORT_AGENT changes are gated like the other privileged operator roles', () => {
    expect(isGatedOperatorAccount(['SUPPORT_AGENT'], 'some-entity')).toBe(true);
  });
});
