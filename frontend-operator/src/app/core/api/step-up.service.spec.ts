import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { SUPPRESS_ERROR_TOAST } from '@registerwerk/ui';
import { environment } from '../../../environments/environment';
import { StepUpService } from './step-up.service';

describe('StepUpService', () => {
    let service: StepUpService;
    let httpMock: HttpTestingController;
    const url = `${environment.apiUrl}/auth/step-up`;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [StepUpService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(StepUpService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('the initiator token carries only the code (no action, no target)', () => {
        service.issueToken('123456').subscribe();
        const req = httpMock.expectOne(url);
        expect(req.request.body.code).toBe('123456');
        expect(req.request.body.action).toBeUndefined();
        expect(req.request.body.target).toBeUndefined();
        req.flush({ stepUpToken: 't' });
    });

    it('an approver token is bound to the request: action + target + body', () => {
        const body = { toAddress: '0xabc', amount: '5' };
        service.issueToken('123456', 'ISSUER_MINT', 'POST /api/v1/assets/a/deployments/d/issuer/mint', body).subscribe();
        const req = httpMock.expectOne(url);
        expect(req.request.body).toMatchObject({
            code: '123456', method: 'TOTP', action: 'ISSUER_MINT',
            target: 'POST /api/v1/assets/a/deployments/d/issuer/mint', targetBody: body,
        });
        req.flush({ stepUpToken: 't' });
    });

    it('a target without an action is not sent', () => {
        service.issueToken('123456', undefined, 'POST /x').subscribe();
        const req = httpMock.expectOne(url);
        expect(req.request.body.target).toBeUndefined();
        req.flush({ stepUpToken: 't' });
    });

    it('every step-up call reports its own failure inline, so none triggers the global "Access denied" toast', () => {
        service.issueToken('123456').subscribe();
        service.enroll('pw').subscribe();
        service.confirmEnrollment('123456').subscribe();
        service.disenroll('123456').subscribe();
        for (const req of httpMock.match(() => true)) {
            expect(req.request.context.get(SUPPRESS_ERROR_TOAST), req.request.url).toBe(true);
            req.flush({});
        }
    });

    it('enroll() re-proves the account password', () => {
        service.enroll('pw').subscribe();
        const req = httpMock.expectOne(`${url}/enroll`);
        expect(req.request.body).toEqual({ currentPassword: 'pw' });
        req.flush({ secret: 's', otpauthUri: 'u' });
    });
});
