import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SecurityService } from './security.service';
import { environment } from '../../../environments/environment';

describe('SecurityService', () => {
    let service: SecurityService;
    let httpMock: HttpTestingController;
    const base = `${environment.apiUrl}/security`;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [SecurityService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(SecurityService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('getTwoFactorStatus() GETs /security/two-factor', () => {
        service.getTwoFactorStatus().subscribe();
        const req = httpMock.expectOne(`${base}/two-factor`);
        expect(req.request.method).toBe('GET');
        req.flush({
            applicable: true,
            identityModel: 'LOCAL',
            managedHere: true,
            registered: true,
            methods: ['TOTP'],
            checkedAt: '2026-08-06T00:00:00Z',
            setupUrl: 'https://mysignins.microsoft.com/security-info',
            message: null,
        });
    });

    it('refreshTwoFactorStatus() POSTs to /security/two-factor/refresh with an empty body', () => {
        service.refreshTwoFactorStatus().subscribe();
        const req = httpMock.expectOne(`${base}/two-factor/refresh`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({});
        req.flush({
            applicable: true,
            identityModel: 'LOCAL',
            managedHere: true,
            registered: false,
            methods: [],
            checkedAt: null,
            setupUrl: 'https://mysignins.microsoft.com/security-info',
            message: null,
        });
    });
});
