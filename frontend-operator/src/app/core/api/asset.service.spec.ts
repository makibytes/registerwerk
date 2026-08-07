import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { AssetService } from './asset.service';

describe('AssetService', () => {
  let service: AssetService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/assets`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AssetService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AssetService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('getAssets() with no params issues a bare GET (no query string)', () => {
    service.getAssets().subscribe();
    const req = httpMock.expectOne(r => r.url === base);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys().length).toBe(0);
    req.flush({ content: [], totalElements: 0 });
  });

  it('getAssets() serializes all provided filter params onto the query string', () => {
    service.getAssets({
      status: 'ISSUED',
      tokenStandard: 'ERC3643',
      issuerId: 'issuer-1',
      search: 'bond',
      page: 2,
      size: 25,
      sort: 'createdAt,desc',
    }).subscribe();

    const req = httpMock.expectOne(r => r.url === base);
    expect(req.request.params.get('status')).toBe('ISSUED');
    expect(req.request.params.get('tokenStandard')).toBe('ERC3643');
    expect(req.request.params.get('issuerId')).toBe('issuer-1');
    expect(req.request.params.get('search')).toBe('bond');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.get('sort')).toBe('createdAt,desc');
    req.flush({ content: [], totalElements: 0 });
  });

  it('getAssets() includes page=0 (falsy but not nullish) in the query string', () => {
    service.getAssets({ page: 0 }).subscribe();
    const req = httpMock.expectOne(r => r.url === base);
    expect(req.request.params.get('page')).toBe('0');
    req.flush({ content: [], totalElements: 0 });
  });

  it('getAsset() GETs the single-resource URL', () => {
    service.getAsset('a-1').subscribe();
    const req = httpMock.expectOne(`${base}/a-1`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('createAsset() POSTs the asset body to the collection URL', () => {
    const body = { name: 'Bond A' };
    service.createAsset(body).subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('updateAsset() PUTs to the resource URL', () => {
    service.updateAsset('a-1', { name: 'Renamed' }).subscribe();
    const req = httpMock.expectOne(`${base}/a-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'Renamed' });
    req.flush({});
  });

  it('approveAsset() / issueAsset() / suspendAsset() / reactivateAsset() / redeemAsset() hit their lifecycle sub-paths with empty bodies', () => {
    service.approveAsset('a-1').subscribe();
    expect(httpMock.expectOne(`${base}/a-1/approve`).request.method).toBe('POST');

    service.issueAsset('a-1').subscribe();
    expect(httpMock.expectOne(`${base}/a-1/issue`).request.method).toBe('POST');

    service.suspendAsset('a-1').subscribe();
    expect(httpMock.expectOne(`${base}/a-1/suspend`).request.method).toBe('POST');

    service.reactivateAsset('a-1').subscribe();
    expect(httpMock.expectOne(`${base}/a-1/reactivate`).request.method).toBe('POST');

    service.redeemAsset('a-1').subscribe();
    expect(httpMock.expectOne(`${base}/a-1/redeem`).request.method).toBe('POST');

    httpMock.match(() => true).forEach(req => req.flush({}));
  });

  it('mint() and burn() POST the correct issuer sub-paths with their bodies', () => {
    service.mint('a-1', 'd-1', { toAddress: '0xabc', amount: 100 }).subscribe();
    const mintReq = httpMock.expectOne(`${base}/a-1/deployments/d-1/issuer/mint`);
    expect(mintReq.request.method).toBe('POST');
    expect(mintReq.request.body).toEqual({ toAddress: '0xabc', amount: 100 });
    mintReq.flush({ txId: 'tx-1' });

    service.burn('a-1', 'd-1', { fromAddress: '0xabc', amount: 50 }).subscribe();
    const burnReq = httpMock.expectOne(`${base}/a-1/deployments/d-1/issuer/burn`);
    expect(burnReq.request.method).toBe('POST');
    expect(burnReq.request.body).toEqual({ fromAddress: '0xabc', amount: 50 });
    burnReq.flush({ txId: 'tx-2' });
  });

  it('uploadDocument() sends a multipart FormData body with file and documentType', () => {
    const file = new File(['content'], 'term-sheet.pdf', { type: 'application/pdf' });
    service.uploadDocument('a-1', file, 'PROSPECTUS').subscribe();

    const req = httpMock.expectOne(`${base}/a-1/documents`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    const form = req.request.body as FormData;
    expect(form.get('documentType')).toBe('PROSPECTUS');
    expect((form.get('file') as File).name).toBe('term-sheet.pdf');
    req.flush({});
  });

  it('uploadDocument() defaults documentType to TERM_SHEET when omitted', () => {
    const file = new File(['x'], 'x.pdf');
    service.uploadDocument('a-1', file).subscribe();
    const req = httpMock.expectOne(`${base}/a-1/documents`);
    expect((req.request.body as FormData).get('documentType')).toBe('TERM_SHEET');
    req.flush({});
  });

  it('downloadDocument() requests a blob response type', () => {
    service.downloadDocument('a-1', 'doc-1').subscribe();
    const req = httpMock.expectOne(`${base}/a-1/documents/doc-1/content`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob());
  });

  it('deleteDocument() DELETEs the document resource', () => {
    service.deleteDocument('a-1', 'doc-1').subscribe();
    const req = httpMock.expectOne(`${base}/a-1/documents/doc-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('getKycCompliance() GETs the kyc-compliance sub-resource', () => {
    service.getKycCompliance('a-1').subscribe();
    const req = httpMock.expectOne(`${base}/a-1/kyc-compliance`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('deployAsset() POSTs chain/network/tokenStandard to the deploy sub-path', () => {
    const body = { chain: 'ethereum', network: 'sepolia', tokenStandard: 'ERC3643' };
    service.deployAsset('a-1', body).subscribe();
    const req = httpMock.expectOne(`${base}/a-1/deploy`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });
});
