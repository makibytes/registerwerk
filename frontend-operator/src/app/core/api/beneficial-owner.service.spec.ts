import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { BeneficialOwnerService } from './beneficial-owner.service';

describe('BeneficialOwnerService', () => {
    let service: BeneficialOwnerService;
    let httpMock: HttpTestingController;
    const base = `${environment.apiUrl}/entities`;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [BeneficialOwnerService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(BeneficialOwnerService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('cease() sends a JSON body on DELETE together with both tokens', () => {
        service.cease('e1', 'bo1', { reason: 'sold shares' }, { stepUpToken: 'su', dualControlToken: 'dc' }).subscribe();
        const req = httpMock.expectOne(`${base}/e1/beneficial-owners/bo1`);
        expect(req.request.method).toBe('DELETE');
        expect(req.request.body).toEqual({ reason: 'sold shares' });
        expect(req.request.headers.get('Authorization')).toBe('Bearer su');
        expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dc');
        req.flush({});
    });

    it('verify() links the owner to a stored document', () => {
        service.verify('e1', 'bo1', 'doc1').subscribe();
        const req = httpMock.expectOne(`${base}/e1/beneficial-owners/bo1/verify`);
        expect(req.request.body).toEqual({ documentId: 'doc1' });
        req.flush({});
    });
});
