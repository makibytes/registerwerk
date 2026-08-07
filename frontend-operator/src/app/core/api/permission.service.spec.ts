import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { PermissionService } from './permission.service';

describe('PermissionService', () => {
  let service: PermissionService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/permissions`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PermissionService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PermissionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('list() GETs the permissions collection', () => {
    service.list().subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('create() POSTs the new permission definition', () => {
    const body = { code: 'bond-desk.publish', name: 'Publish bond desk' };
    service.create(body).subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('grants() GETs the grants sub-resource for a definition', () => {
    service.grants('def-1').subscribe();
    const req = httpMock.expectOne(`${base}/def-1/grants`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('grantToOrg() POSTs the org registration id and attaches step-up + dual-control headers', () => {
    service.grantToOrg('def-1', 'org-1', 'step-up-jwt', 'dual-control-jwt').subscribe();

    const req = httpMock.expectOne(`${base}/def-1/org-grants`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ orgRegistrationId: 'org-1' });
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
    req.flush({});
  });

  it('revokeGrant() DELETEs the org-grant with step-up + dual-control headers', () => {
    service.revokeGrant('grant-1', 'step-up-jwt', 'dual-control-jwt').subscribe();

    const req = httpMock.expectOne(`${base}/org-grants/grant-1`);
    expect(req.request.method).toBe('DELETE');
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
    req.flush({});
  });

  it('trustedIssuers() GETs the trusted-issuers sub-resource', () => {
    service.trustedIssuers().subscribe();
    const req = httpMock.expectOne(`${base}/trusted-issuers`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('addTrustedIssuer() POSTs the issuer body with auth headers', () => {
    const body = { chainConfigId: 'c-1', issuerAddress: '0xabc', claimTopics: [1, 2] };
    service.addTrustedIssuer(body, 'step-up-jwt', 'dual-control-jwt').subscribe();

    const req = httpMock.expectOne(`${base}/trusted-issuers`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
    req.flush({});
  });

  it('removeTrustedIssuer() DELETEs the trusted-issuer with auth headers', () => {
    service.removeTrustedIssuer('issuer-1', 'step-up-jwt', 'dual-control-jwt').subscribe();

    const req = httpMock.expectOne(`${base}/trusted-issuers/issuer-1`);
    expect(req.request.method).toBe('DELETE');
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
    req.flush({});
  });
});
