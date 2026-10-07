import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LendingAdminService, summarizeLegacyPause } from './lending-admin.service';
import { environment } from '../../../environments/environment';
import { IDEMPOTENT_ENDPOINTS } from '../interceptors/idempotency.interceptor';

describe('LendingAdminService', () => {
  let service: LendingAdminService;
  let http: HttpTestingController;
  const base = `${environment.apiUrl}/lending`;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(LendingAdminService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('keeps token base units above 2^53 exact in reconciliation tasks', () => {
    let shortfall: string | null = null;
    service.listReconciliationTasks().subscribe((tasks) => (shortfall = tasks[0].shortfall));
    http.expectOne(`${base}/reconciliation-tasks`).flush('[{"id":"t","shortfall":12345678901234567890123}]');
    expect(shortfall).toBe('12345678901234567890123');
  });

  it('registerMarket() sends step-up bearer and second approver token', () => {
    service.registerMarket(
      { chainConfigId: 'c', marketAddress: '0x1', vaultAddress: null, collateralAssetId: 'a', loanRailCode: 'aueur' },
      { stepUpToken: 'su', dualControlToken: 'dc' },
    ).subscribe();
    const req = http.expectOne(`${base}/markets`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer su');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
    req.flush({});
  });

  it('the idempotency interceptor covers every @RequiresIdempotencyKey lending endpoint', () => {
    const urls = [
      `${base}/markets`,
      `${base}/markets/reverify`,
      `${base}/markets/legacy-borrow-pause`,
      `${base}/markets/11111111-1111-1111-1111-111111111111/reconcile-collateral`,
      `${base}/markets/11111111-1111-1111-1111-111111111111/borrow-paused`,
    ];
    for (const url of urls) {
      expect(IDEMPOTENT_ENDPOINTS.some((p) => p.test(url)), url).toBe(true);
    }
    expect(IDEMPOTENT_ENDPOINTS.some((p) => p.test(`${base}/markets/x/quote`))).toBe(false);
  });

  it('legacyBorrowPause() posts an empty body with step-up bearer and second approver token', () => {
    let outcome = '';
    service.legacyBorrowPause({ stepUpToken: 'su', dualControlToken: 'dc' })
      .subscribe((r) => (outcome = r[0].outcome));
    const req = http.expectOne(`${base}/markets/legacy-borrow-pause`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    expect(req.request.headers.get('Authorization')).toBe('Bearer su');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
    req.flush([{ marketId: 'm', marketAddress: '0x1', outcome: 'SUBMITTED', txHash: null }]);
    expect(outcome).toBe('SUBMITTED');
  });

  it('summarizeLegacyPause() never counts an unreadable market as paused', () => {
    expect(summarizeLegacyPause([])).toMatch(/No unverified/);
    const text = summarizeLegacyPause([
      { marketId: '1', marketAddress: '0x1', outcome: 'SUBMITTED', txHash: null },
      { marketId: '2', marketAddress: '0x2', outcome: 'NOT_CHECKED', txHash: null },
      { marketId: '3', marketAddress: '0x3', outcome: 'ALREADY_PAUSED', txHash: null },
    ]);
    expect(text).toContain('1 pause transaction submitted');
    expect(text).toContain('1 already paused');
    expect(text).toContain('1 could not be read on-chain and were NOT paused');
  });
});
