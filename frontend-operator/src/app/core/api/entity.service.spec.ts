import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { EntityService } from './entity.service';

describe('EntityService lifecycle calls', () => {
    let service: EntityService;
    let httpMock: HttpTestingController;
    const base = `${environment.apiUrl}/entities`;
    const tokens = { stepUpToken: 'su', dualControlToken: 'dc' };

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [EntityService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(EntityService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('suspend and reactivate send a reason, the step-up bearer and the approver token', () => {
        service.suspendEntity('e1', 'why', tokens).subscribe();
        const s = httpMock.expectOne(`${base}/e1/suspend`);
        expect(s.request.body).toEqual({ reason: 'why' });
        expect(s.request.headers.get('Authorization')).toBe('Bearer su');
        expect(s.request.headers.get('X-Dual-Control-Token')).toBe('dc');
        s.flush({});
        service.reactivateEntity('e1', 'ok', tokens).subscribe();
        const r = httpMock.expectOne(`${base}/e1/reactivate`);
        expect(r.request.body).toEqual({ reason: 'ok' });
        r.flush({});
    });

    it('terminate carries the acknowledged obligations', () => {
        service.terminateEntity('e1', 'exit', [{ obligationId: 'ASSET:1', reason: 'exit' }], tokens).subscribe();
        const req = httpMock.expectOne(`${base}/e1/terminate`);
        expect(req.request.body).toEqual({ reason: 'exit', acknowledgedObligations: [{ obligationId: 'ASSET:1', reason: 'exit' }] });
        req.flush({});
    });

    it('merge needs a reason and both tokens', () => {
        service.mergeEntity('e1', {
            targetEntityId: 't', mergeType: 'ABSORPTION', effectiveDate: '2026-01-01', reason: 'M&A',
        }, tokens).subscribe();
        const req = httpMock.expectOne(`${base}/e1/merge`);
        expect(req.request.body.reason).toBe('M&A');
        expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
        req.flush({});
    });

    it('classification sends the approver token only when given', () => {
        service.classifyClient('e1', { clientCategory: 'RETAIL' }, 'su').subscribe();
        const a = httpMock.expectOne(`${base}/e1/classification`);
        expect(a.request.headers.has('X-Dual-Control-Token')).toBe(false);
        a.flush({});
        service.classifyClient('e1', { clientCategory: 'ELIGIBLE_COUNTERPARTY', reason: 'x' }, 'su', 'dc').subscribe();
        const b = httpMock.expectOne(`${base}/e1/classification`);
        expect(b.request.headers.get('X-Dual-Control-Token')).toBe('dc');
        b.flush({});
    });
});
