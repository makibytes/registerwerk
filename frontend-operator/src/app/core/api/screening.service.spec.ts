import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { ScreeningService } from './screening.service';

describe('ScreeningService', () => {
    let service: ScreeningService;
    let httpMock: HttpTestingController;
    const base = `${environment.apiUrl}/compliance/screening`;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [ScreeningService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(ScreeningService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('listOpenHits() GETs the open-hit work queue', () => {
        service.listOpenHits().subscribe();
        const req = httpMock.expectOne(`${base}/hits?status=open`);
        expect(req.request.method).toBe('GET');
        req.flush([]);
    });

    it('listRunsByEntity() GETs runs scoped to a legal entity', () => {
        service.listRunsByEntity('entity-1').subscribe();
        const req = httpMock.expectOne(`${base}/entities/entity-1/runs`);
        expect(req.request.method).toBe('GET');
        req.flush([]);
    });

    it('getRun() GETs a single run by id', () => {
        service.getRun('run-1').subscribe();
        const req = httpMock.expectOne(`${base}/runs/run-1`);
        expect(req.request.method).toBe('GET');
        req.flush({});
    });

    it('listHitsByRun() GETs hits scoped to a run', () => {
        service.listHitsByRun('run-1').subscribe();
        const req = httpMock.expectOne(`${base}/runs/run-1/hits`);
        expect(req.request.method).toBe('GET');
        req.flush([]);
    });

    it('screenEntity() POSTs the entity screening request body', () => {
        const body = { name: 'Acme GmbH', countryCode: 'DE' };
        service.screenEntity('entity-1', body).subscribe();
        const req = httpMock.expectOne(`${base}/entities/entity-1/screen`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual(body);
        req.flush({});
    });

    it('screenPerson() POSTs the natural-person screening request body', () => {
        const body = { fullName: 'Jane Doe', countryCode: 'DE' };
        service.screenPerson('person-1', body).subscribe();
        const req = httpMock.expectOne(`${base}/persons/person-1/screen`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual(body);
        req.flush({});
    });

    it('acceptHit() attaches only the step-up Authorization header when no dual-control token is given', () => {
        service.acceptHit('hit-1', { reason: 'false positive' }, 'step-up-jwt').subscribe();

        const req = httpMock.expectOne(`${base}/hits/hit-1/accept`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ reason: 'false positive' });
        expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
        expect(req.request.headers.has('X-Dual-Control-Token')).toBe(false);
        req.flush({});
    });

    it('acceptHit() attaches both step-up and dual-control headers when a dual-control token is given', () => {
        service.acceptHit('hit-1', { reason: 'false positive', approverActorId: 'u-2' }, 'step-up-jwt', 'dual-control-jwt').subscribe();

        const req = httpMock.expectOne(`${base}/hits/hit-1/accept`);
        expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
        expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
        req.flush({});
    });
});
