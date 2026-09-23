import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { VaultService } from './vault.service';

describe('VaultService', () => {
    let service: VaultService;
    let httpMock: HttpTestingController;
    const base = `${environment.apiUrl}/deployments/dep-1`;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [VaultService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(VaultService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('fulfillRequest() never sends an operator-typed NAV (the chain decides the executed NAV)', () => {
        service.fulfillRequest('dep-1', '7').subscribe();
        const req = httpMock.expectOne(`${base}/vault-requests/7/fulfill`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({});
        req.flush({ txId: 'tx-1' });
    });

    it('getVaultState() GETs the confirmed vault state', () => {
        service.getVaultState('dep-1').subscribe();
        const req = httpMock.expectOne(`${base}/vault-state`);
        expect(req.request.method).toBe('GET');
        req.flush({ assetId: 'a-1', latestNavPerShare: 1.05 });
    });

    it('forceCancelRequest() POSTs destination + legal basis with step-up and dual-control tokens', () => {
        service.forceCancelRequest('dep-1', '9', { to: '0xabc', legalBasis: 'court order' }, 'step-up', 'second')
            .subscribe();
        const req = httpMock.expectOne(`${base}/vault-requests/9/force-cancel`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ to: '0xabc', legalBasis: 'court order' });
        expect(req.request.headers.get('Authorization')).toBe('Bearer step-up');
        expect(req.request.headers.get('X-Dual-Control-Token')).toBe('second');
        req.flush({ txId: 'tx-2' });
    });
});
