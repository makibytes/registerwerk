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
});
