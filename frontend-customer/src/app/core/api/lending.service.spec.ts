import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LendingService } from './lending.service';
import { environment } from '../../../environments/environment';

describe('LendingService - lender eligibility preflight (T2-20)', () => {
  let service: LendingService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(LendingService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads the gate state from /lending/lender-eligibility', () => {
    let result: unknown;
    service.lenderEligibility().subscribe((r) => (result = r));
    http.expectOne(`${environment.apiUrl}/lending/lender-eligibility`)
      .flush({ productionMode: true, eligible: false, reasons: ['KYC is not APPROVED'] });
    expect(result).toEqual({ productionMode: true, eligible: false, reasons: ['KYC is not APPROVED'] });
  });
});
