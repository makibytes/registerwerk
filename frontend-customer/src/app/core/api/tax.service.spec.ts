import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TaxService } from './tax.service';
import { environment } from '../../../environments/environment';

describe('TaxService', () => {
    let service: TaxService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [TaxService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(TaxService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('downloadMyTaxCertificate() GETs the year-scoped URL with a Blob response type', () => {
        service.downloadMyTaxCertificate(2025).subscribe();
        const req = httpMock.expectOne(`${environment.apiUrl}/me/tax-certificates/2025`);
        expect(req.request.method).toBe('GET');
        expect(req.request.responseType).toBe('blob');
        req.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }));
    });
});
