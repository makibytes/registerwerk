import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SubscriptionOrderService } from './subscription-order.service';
import { IssuanceService } from './issuance.service';
import { environment } from '../../../environments/environment';

describe('SubscriptionOrderService (T3-08)', () => {
    let service: SubscriptionOrderService;
    let httpMock: HttpTestingController;
    const base = environment.apiUrl;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [SubscriptionOrderService, IssuanceService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(SubscriptionOrderService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('accept() posts to /accept and no longer implies a register entry', () => {
        service.accept('order-1').subscribe();
        const req = httpMock.expectOne(`${base}/orders/order-1/accept`);
        expect(req.request.method).toBe('POST');
        req.flush({});
    });

    it('issuer register entries are requests, not direct writes (T3-13)', () => {
        TestBed.inject(IssuanceService).requestHolderEntry('asset-1', {
            investorId: 'inv-1', walletAddress: '0xabc', nominalAmount: 5,
            instructingParty: 'COURT', instructionReference: 'AZ 1',
        }).subscribe();
        const req = httpMock.expectOne(`${base}/assets/asset-1/holders/change-requests`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body.requestType).toBe('ADD_HOLDER');
        expect(req.request.body.instructionReference).toBe('AZ 1');
        req.flush({});
    });
});
