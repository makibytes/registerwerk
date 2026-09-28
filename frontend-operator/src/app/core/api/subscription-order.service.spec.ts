import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { SubscriptionOrderService } from './subscription-order.service';
import { RegisterEntryService } from './register-entry.service';

describe('SubscriptionOrderService / RegisterEntryService (T3-08, T3-13)', () => {
    let httpMock: HttpTestingController;
    const base = environment.apiUrl;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [SubscriptionOrderService, RegisterEntryService, provideHttpClient(), provideHttpClientTesting()],
        });
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('confirmPayment() sends the amount, reference and the step-up token', () => {
        TestBed.inject(SubscriptionOrderService)
            .confirmPayment('o-1', { paidAmount: 100, paymentReference: 'SUB-1' }, 'tok').subscribe();
        const req = httpMock.expectOne(`${base}/orders/o-1/confirm-payment`);
        expect(req.request.method).toBe('POST');
        expect(req.request.headers.get('Authorization')).toBe('Bearer tok');
        expect(req.request.body.paidAmount).toBe(100);
        req.flush({});
    });

    it('settle() and release() carry the step-up token', () => {
        const service = TestBed.inject(SubscriptionOrderService);
        service.settle('o-1', 'tok').subscribe();
        service.release('o-1', 'KYC expired', 'tok').subscribe();
        const settle = httpMock.expectOne(`${base}/orders/o-1/settle`);
        expect(settle.request.headers.get('Authorization')).toBe('Bearer tok');
        settle.flush({});
        const release = httpMock.expectOne(`${base}/orders/o-1/release`);
        expect(release.request.body.reason).toBe('KYC expired');
        release.flush({});
    });

    it('updateAttributes() needs both dual-control tokens and sends the explicit clear flags', () => {
        TestBed.inject(RegisterEntryService).updateAttributes('a-1', 'h-1', {
            instructingParty: 'COURT', instructionReference: 'AZ 1',
            clearThirdPartyRights: true, clearDisposalRestrictions: false,
        }, { stepUpToken: 's', dualControlToken: 'd' }).subscribe();
        const req = httpMock.expectOne(`${base}/assets/a-1/holders/h-1/single-entry-attributes`);
        expect(req.request.method).toBe('PATCH');
        expect(req.request.headers.get('X-Dual-Control-Token')).toBe('d');
        expect(req.request.body.clearThirdPartyRights).toBe(true);
        req.flush({});
    });
});
