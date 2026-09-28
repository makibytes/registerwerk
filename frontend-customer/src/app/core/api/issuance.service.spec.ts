import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { IssuanceService } from './issuance.service';
import { environment } from '../../../environments/environment';

describe('IssuanceService', () => {
    let service: IssuanceService;
    let httpMock: HttpTestingController;
    const base = `${environment.apiUrl}/assets`;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [IssuanceService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(IssuanceService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('loads live holders for a specific asset deployment', () => {
        service.getLiveHolders('asset-1', 'deployment-1').subscribe((holders) => {
            expect(holders.length).toBe(1);
            expect(holders[0].walletAddress).toBe('0xabc');
        });

        const req = httpMock.expectOne(`${base}/asset-1/holders/deployment-1/live`);
        expect(req.request.method).toBe('GET');
        req.flush([{ walletAddress: '0xabc', tokenBalance: 10, isWhitelisted: true }]);
    });

    it('burn() sends the operator approval token and the step-up bearer (T3-01)', () => {
        service.burn('asset-1', 'dep-1', { fromAddress: '0xabc', amount: '5' },
            { approvalToken: 'approver-jwt', stepUpToken: 'stepup-jwt' }).subscribe();

        const req = httpMock.expectOne(`${base}/asset-1/deployments/dep-1/issuer/burn`);
        expect(req.request.method).toBe('POST');
        expect(req.request.headers.get('X-Dual-Control-Token')).toBe('approver-jwt');
        expect(req.request.headers.get('Authorization')).toBe('Bearer stepup-jwt');
        req.flush({ txId: 'tx-1' });
    });

    it('burn() without a step-up token leaves Authorization to the interceptor (Entra claims challenge)', () => {
        service.burn('asset-1', 'dep-1', { fromAddress: '0xabc', amount: '5' },
            { approvalToken: 'approver-jwt' }).subscribe();

        const req = httpMock.expectOne(`${base}/asset-1/deployments/dep-1/issuer/burn`);
        expect(req.request.headers.has('Authorization')).toBe(false);
        expect(req.request.headers.get('X-Dual-Control-Token')).toBe('approver-jwt');
        req.flush({ txId: 'tx-1' });
    });

    it('stepUp() requests a token scoped to the action', () => {
        service.stepUp('123456', 'ISSUER_BURN_EWG26').subscribe();

        const req = httpMock.expectOne(`${environment.apiUrl}/auth/step-up`);
        expect(req.request.body).toEqual({ code: '123456', method: 'TOTP', action: 'ISSUER_BURN_EWG26' });
        req.flush({ stepUpToken: 'x' });
    });
});
