import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CompanyService } from './company.service';
import { environment } from '../../../environments/environment';

describe('CompanyService', () => {
  let service: CompanyService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/company`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [CompanyService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CompanyService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('getMyEntity() issues a GET to /company/me', () => {
    service.getMyEntity().subscribe();
    const req = httpMock.expectOne(`${base}/me`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('listExternalIds() omits the subjectType param when not given', () => {
    service.listExternalIds().subscribe();
    const req = httpMock.expectOne(r => r.url === `${base}/external-ids`);
    expect(req.request.params.has('subjectType')).toBe(false);
    req.flush([]);
  });

  it('listExternalIds() sends subjectType as a query param when given', () => {
    service.listExternalIds('ASSET').subscribe();
    const req = httpMock.expectOne(r => r.url === `${base}/external-ids`);
    expect(req.request.params.get('subjectType')).toBe('ASSET');
    req.flush([]);
  });

  it('saveExternalId() PUTs to the subject-scoped URL with the externalId body', () => {
    service.saveExternalId('ASSET', 'asset-1', 'ISIN123').subscribe();
    const req = httpMock.expectOne(`${base}/external-ids/ASSET/asset-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ externalId: 'ISIN123' });
    req.flush({});
  });

  it('deleteExternalId() issues a DELETE to the subject-scoped URL', () => {
    service.deleteExternalId('ASSET', 'asset-1').subscribe();
    const req = httpMock.expectOne(`${base}/external-ids/ASSET/asset-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('inviteUser() POSTs the invitation payload', () => {
    const body = { email: 'a@b.com', name: 'A B', roles: ['INVESTOR' as const] };
    service.inviteUser(body).subscribe();
    const req = httpMock.expectOne(`${base}/users/invite`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('updateUserRoles() PATCHes the roles array', () => {
    service.updateUserRoles('user-1', ['COMPANY_ADMIN']).subscribe();
    const req = httpMock.expectOne(`${base}/users/user-1/roles`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ roles: ['COMPANY_ADMIN'] });
    req.flush({});
  });

  it('disableUser() / enableUser() POST to their respective action URLs', () => {
    service.disableUser('user-1').subscribe();
    const disableReq = httpMock.expectOne(`${base}/users/user-1/disable`);
    expect(disableReq.request.method).toBe('POST');
    disableReq.flush({});

    service.enableUser('user-1').subscribe();
    const enableReq = httpMock.expectOne(`${base}/users/user-1/enable`);
    expect(enableReq.request.method).toBe('POST');
    enableReq.flush({});
  });

  it('getIdpSettings() / saveIdpSettings() hit /company/idp with GET and PUT', () => {
    service.getIdpSettings().subscribe();
    httpMock.expectOne({ method: 'GET', url: `${base}/idp` }).flush({});

    service.saveIdpSettings({ issuerUrl: 'https://idp.example.com', clientId: 'c1' }).subscribe();
    const putReq = httpMock.expectOne({ method: 'PUT', url: `${base}/idp` });
    expect(putReq.request.body).toEqual({ issuerUrl: 'https://idp.example.com', clientId: 'c1' });
    putReq.flush({});
  });

  it('completeRegistration() POSTs token, name and password to the public endpoint', () => {
    service.completeRegistration('reg-tok', 'A B', 'secret').subscribe();
    const req = httpMock.expectOne(`${environment.apiUrl}/public/company-users/registration/complete`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'reg-tok', name: 'A B', password: 'secret' });
    req.flush(null);
  });

  it('completePasswordReset() POSTs token and password to the public endpoint', () => {
    service.completePasswordReset('reset-tok', 'newpw').subscribe();
    const req = httpMock.expectOne(`${environment.apiUrl}/public/company-users/password-reset/complete`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'reset-tok', password: 'newpw' });
    req.flush(null);
  });
});
