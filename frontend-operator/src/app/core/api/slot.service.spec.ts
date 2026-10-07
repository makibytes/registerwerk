import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { SlotService } from './slot.service';

describe('SlotService dual-control (9X-1)', () => {
  let service: SlotService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/deployments/dep-1`;
  const tokens = { stepUpToken: 'su-jwt', dualControlToken: 'dc-jwt' };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [SlotService, provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(SlotService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('createSlot carries the step-up bearer and the second approver token', () => {
    service.createSlot('dep-1', { slotId: '7' }, tokens).subscribe();
    const req = httpMock.expectOne(`${base}/slots`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer su-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc-jwt');
    req.flush({ txId: 't' });
  });

  it('mintIntoSlot carries the step-up bearer and the second approver token', () => {
    service.mintIntoSlot('dep-1', '7', { toAddress: '0xabc', value: '1' }, tokens).subscribe();
    const req = httpMock.expectOne(`${base}/slots/7/mint`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer su-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc-jwt');
    req.flush({ txId: 't' });
  });
});
