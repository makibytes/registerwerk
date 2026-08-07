import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { KycService } from './kyc.service';

describe('KycService', () => {
  let service: KycService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/entities`;
  const publicBase = `${environment.apiUrl}/public`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [KycService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(KycService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listDocuments() GETs the kyc/documents sub-resource', () => {
    service.listDocuments('entity-1').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/documents`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('uploadDocument() sends multipart FormData with file, documentType, and optional jurisdiction', () => {
    const file = new File(['x'], 'passport.pdf', { type: 'application/pdf' });
    service.uploadDocument('entity-1', file, 'PASSPORT', 'DE_EWPG').subscribe();

    const req = httpMock.expectOne(`${base}/entity-1/kyc/documents`);
    expect(req.request.method).toBe('POST');
    const form = req.request.body as FormData;
    expect((form.get('file') as File).name).toBe('passport.pdf');
    expect(form.get('documentType')).toBe('PASSPORT');
    expect(form.get('jurisdiction')).toBe('DE_EWPG');
    req.flush({});
  });

  it('uploadDocument() omits the jurisdiction field entirely when not provided', () => {
    const file = new File(['x'], 'id.pdf');
    service.uploadDocument('entity-1', file, 'ID_CARD').subscribe();

    const req = httpMock.expectOne(`${base}/entity-1/kyc/documents`);
    const form = req.request.body as FormData;
    expect(form.has('jurisdiction')).toBeFalse();
    req.flush({});
  });

  it('downloadDocument() requests a blob response', () => {
    service.downloadDocument('entity-1', 'doc-1').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/documents/doc-1`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob());
  });

  it('deleteDocument() DELETEs the document resource', () => {
    service.deleteDocument('entity-1', 'doc-1').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/documents/doc-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('approveKyc() POSTs the expiry date to the approve sub-path', () => {
    service.approveKyc('entity-1', '2027-01-01').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/approve`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ expiryDate: '2027-01-01' });
    req.flush({});
  });

  it('rejectKyc() POSTs the reason to the reject sub-path', () => {
    service.rejectKyc('entity-1', 'Document expired').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/reject`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Document expired' });
    req.flush({});
  });

  it('getJurisdictionApprovals() GETs the per-jurisdiction approvals list', () => {
    service.getJurisdictionApprovals('entity-1').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/jurisdictions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('approveJurisdiction() POSTs an empty body when no expiry is given', () => {
    service.approveJurisdiction('entity-1', 'DE_EWPG').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/jurisdictions/DE_EWPG/approve`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({});
  });

  it('approveJurisdiction() includes expiresAt in the body when given', () => {
    service.approveJurisdiction('entity-1', 'DE_EWPG', '2027-01-01').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/jurisdictions/DE_EWPG/approve`);
    expect(req.request.body).toEqual({ expiresAt: '2027-01-01' });
    req.flush({});
  });

  it('rejectJurisdiction() POSTs the rejection reason', () => {
    service.rejectJurisdiction('entity-1', 'DE_EWPG', 'Sanctions concern').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/jurisdictions/DE_EWPG/reject`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Sanctions concern' });
    req.flush({});
  });

  it('getCompliance() GETs the per-jurisdiction compliance report', () => {
    service.getCompliance('entity-1', 'DE_EWPG').subscribe();
    const req = httpMock.expectOne(`${base}/entity-1/kyc/compliance/DE_EWPG`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('getJurisdictionRequirements() GETs the public jurisdictions list (no auth base)', () => {
    service.getJurisdictionRequirements().subscribe();
    const req = httpMock.expectOne(`${publicBase}/jurisdictions`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
