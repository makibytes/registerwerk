import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { AuditService } from './audit.service';

describe('AuditService', () => {
    let service: AuditService;
    let httpMock: HttpTestingController;
    const eventsBase = `${environment.apiUrl}/audit/events`;
    const auditBase = `${environment.apiUrl}/audit`;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [AuditService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(AuditService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('searchEvents() with no params issues a bare GET against the events collection', () => {
        service.searchEvents().subscribe();
        const req = httpMock.expectOne(r => r.url === eventsBase);
        expect(req.request.method).toBe('GET');
        expect(req.request.params.keys().length).toBe(0);
        req.flush({ content: [], totalElements: 0 });
    });

    it('searchEvents() serializes every provided filter onto the query string', () => {
        service.searchEvents({
            eventType: 'ASSET_ISSUED',
            subjectType: 'ASSET',
            subjectId: 'a-1',
            actorId: 'u-1',
            from: '2026-01-01',
            to: '2026-02-01',
            page: 3,
            size: 50,
        }).subscribe();

        const req = httpMock.expectOne(r => r.url === eventsBase);
        expect(req.request.params.get('eventType')).toBe('ASSET_ISSUED');
        expect(req.request.params.get('subjectType')).toBe('ASSET');
        expect(req.request.params.get('subjectId')).toBe('a-1');
        expect(req.request.params.get('actorId')).toBe('u-1');
        expect(req.request.params.get('from')).toBe('2026-01-01');
        expect(req.request.params.get('to')).toBe('2026-02-01');
        expect(req.request.params.get('page')).toBe('3');
        expect(req.request.params.get('size')).toBe('50');
        req.flush({ content: [], totalElements: 0 });
    });

    it('kycOverrideReport() GETs the kyc-overrides report with jurisdiction/date filters', () => {
        service.kycOverrideReport({ jurisdiction: 'DE', from: '2026-01-01', page: 1, size: 10 }).subscribe();
        const req = httpMock.expectOne(r => r.url === `${auditBase}/reports/kyc-overrides`);
        expect(req.request.method).toBe('GET');
        expect(req.request.params.get('jurisdiction')).toBe('DE');
        expect(req.request.params.get('from')).toBe('2026-01-01');
        expect(req.request.params.get('page')).toBe('1');
        expect(req.request.params.get('size')).toBe('10');
        req.flush({ content: [], totalElements: 0 });
    });

    it('getEvent() GETs the single-event resource URL', () => {
        service.getEvent('evt-1').subscribe();
        const req = httpMock.expectOne(`${eventsBase}/evt-1`);
        expect(req.request.method).toBe('GET');
        req.flush({});
    });

    it('chainStatus() GETs the hash-chain status endpoint', () => {
        service.chainStatus().subscribe();
        const req = httpMock.expectOne(`${auditBase}/chain/status`);
        expect(req.request.method).toBe('GET');
        req.flush({ valid: true, rowsChecked: 100, checkedAt: '2026-08-07T00:00:00Z' });
    });

    it('verifyChainNow() POSTs to trigger an on-demand hash-chain verification', () => {
        service.verifyChainNow().subscribe();
        const req = httpMock.expectOne(`${auditBase}/chain/verify`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({});
        req.flush({ valid: true, rowsChecked: 100, checkedAt: '2026-08-07T00:00:00Z' });
    });
});
