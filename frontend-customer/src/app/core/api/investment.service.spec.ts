import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { InvestmentService } from './investment.service';
import { environment } from '../../../environments/environment';

describe('InvestmentService', () => {
    let service: InvestmentService;
    let httpMock: HttpTestingController;
    const base = `${environment.apiUrl}/investments`;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [InvestmentService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(InvestmentService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('getMyInvestments() GETs the base URL with no params when called without args', () => {
        service.getMyInvestments().subscribe();
        const req = httpMock.expectOne(r => r.url === base);
        expect(req.request.method).toBe('GET');
        expect(req.request.params.keys().length).toBe(0);
        req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    });

    it('getMyInvestments() forwards page params, skipping undefined/null values', () => {
        service.getMyInvestments({ page: 1, size: 25 } as never).subscribe();
        const req = httpMock.expectOne(r => r.url === base);
        expect(req.request.params.get('page')).toBe('1');
        expect(req.request.params.get('size')).toBe('25');
        req.flush({ content: [], totalElements: 0, totalPages: 0, number: 1, size: 25 });
    });

    it('getInvestment() GETs the holder-scoped URL', () => {
        service.getInvestment('holder-1').subscribe();
        const req = httpMock.expectOne(`${base}/holder-1`);
        expect(req.request.method).toBe('GET');
        req.flush({});
    });
});
