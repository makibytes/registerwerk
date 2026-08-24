import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminService } from './admin.service';
import { environment } from '../../../environments/environment';

describe('AdminService', () => {
    let service: AdminService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [AdminService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(AdminService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('listEntities() GETs /entities with page/size defaults and no search param when omitted', () => {
        service.listEntities().subscribe();
        const req = httpMock.expectOne(r => r.url === `${environment.apiUrl}/entities`);
        expect(req.request.method).toBe('GET');
        expect(req.request.params.get('page')).toBe('0');
        expect(req.request.params.get('size')).toBe('50');
        expect(req.request.params.has('search')).toBe(false);
        req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 });
    });

    it('listEntities() forwards a search term and custom paging', () => {
        service.listEntities('acme', 2, 10).subscribe();
        const req = httpMock.expectOne(r => r.url === `${environment.apiUrl}/entities`);
        expect(req.request.params.get('search')).toBe('acme');
        expect(req.request.params.get('page')).toBe('2');
        expect(req.request.params.get('size')).toBe('10');
        req.flush({ content: [], totalElements: 0, totalPages: 0, number: 2, size: 10 });
    });

    it('impersonate() POSTs to the dedicated /impersonation URL — deliberately outside /admin/**', () => {
        service.impersonate('ent-1').subscribe();
        // Regression-relevant: this must NOT be under /api/v1/admin/**, which carries an
        // operator-network ip-restriction plugin that the customer portal cannot reach through Kong.
        const req = httpMock.expectOne(`${environment.apiUrl}/impersonation`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ entityId: 'ent-1' });
        req.flush({
            token: 'tok',
            tokenType: 'Bearer',
            expiresAt: '2026-01-01T00:00:00Z',
            entityId: 'ent-1',
            entityName: 'Acme',
        });
    });
});
