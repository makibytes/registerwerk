import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { KycService } from './kyc.service';
import { environment } from '../../../environments/environment';

describe('KycService', () => {
  let service: KycService;
  let httpMock: HttpTestingController;
  const entitiesBase = `${environment.apiUrl}/entities`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [KycService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(KycService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listDocuments() GETs the entity-scoped documents URL', () => {
    service.listDocuments('ent-1').subscribe();
    const req = httpMock.expectOne(`${entitiesBase}/ent-1/kyc/documents`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('uploadDocument() POSTs multipart form data including file, type and jurisdiction', () => {
    const file = new File(['content'], 'passport.pdf', { type: 'application/pdf' });
    service.uploadDocument('ent-1', file, 'PASSPORT', 'DE_EWPG').subscribe();

    const req = httpMock.expectOne(`${entitiesBase}/ent-1/kyc/documents`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeInstanceOf(FormData);
    const body = req.request.body as FormData;
    expect(body.get('documentType')).toBe('PASSPORT');
    expect(body.get('jurisdiction')).toBe('DE_EWPG');
    expect((body.get('file') as File).name).toBe('passport.pdf');
    req.flush({});
  });

  it('uploadDocument() omits the jurisdiction field entirely when not given', () => {
    const file = new File(['content'], 'id.pdf', { type: 'application/pdf' });
    service.uploadDocument('ent-1', file, 'ID_CARD').subscribe();

    const req = httpMock.expectOne(`${entitiesBase}/ent-1/kyc/documents`);
    const body = req.request.body as FormData;
    expect(body.has('jurisdiction')).toBe(false);
    req.flush({});
  });

  it('downloadDocument() requests a Blob response type', () => {
    service.downloadDocument('ent-1', 'doc-1').subscribe();
    const req = httpMock.expectOne(`${entitiesBase}/ent-1/kyc/documents/doc-1`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob());
  });

  it('deleteDocument() issues a DELETE', () => {
    service.deleteDocument('ent-1', 'doc-1').subscribe();
    const req = httpMock.expectOne(`${entitiesBase}/ent-1/kyc/documents/doc-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('getCompliance() GETs the jurisdiction-scoped compliance URL', () => {
    service.getCompliance('ent-1', 'FR_AMF').subscribe();
    const req = httpMock.expectOne(`${entitiesBase}/ent-1/kyc/compliance/FR_AMF`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('getJurisdictionRequirements() GETs the public jurisdictions endpoint', () => {
    service.getJurisdictionRequirements().subscribe();
    const req = httpMock.expectOne(`${environment.apiUrl}/public/jurisdictions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('getKycStatus() GETs the entity-scoped status endpoint', () => {
    service.getKycStatus('ent-1').subscribe();
    const req = httpMock.expectOne(`${entitiesBase}/ent-1/kyc/status`);
    expect(req.request.method).toBe('GET');
    req.flush({ kycStatus: 'APPROVED' });
  });

  it('getJurisdictionApprovals() GETs the entity-scoped jurisdictions endpoint', () => {
    service.getJurisdictionApprovals('ent-1').subscribe();
    const req = httpMock.expectOne(`${entitiesBase}/ent-1/kyc/jurisdictions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
