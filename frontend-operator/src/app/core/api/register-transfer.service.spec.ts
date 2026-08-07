import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { RegisterTransferService } from './register-transfer.service';

describe('RegisterTransferService', () => {
  let service: RegisterTransferService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/register-transfers`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [RegisterTransferService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RegisterTransferService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listForAsset() GETs transfers scoped to an asset', () => {
    service.listForAsset('asset-1').subscribe();
    const req = httpMock.expectOne(`${base}/assets/asset-1`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('initiate() POSTs the full transfer-initiation body to the collection URL', () => {
    service.initiate('asset-1', 'Successor Registrar GmbH', 'LEI123', 'Business closure', 'user-1').subscribe();

    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      assetId: 'asset-1',
      successorName: 'Successor Registrar GmbH',
      successorIdentifier: 'LEI123',
      reason: 'Business closure',
      initiatedBy: 'user-1',
    });
    req.flush({});
  });

  it('export() POSTs to the export sub-path and expects a blob response', () => {
    service.export('transfer-1').subscribe();
    const req = httpMock.expectOne(`${base}/transfer-1/export`);
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob());
  });

  it('recordOnchainHandover() POSTs the tx hash with step-up + dual-control headers', () => {
    service.recordOnchainHandover('transfer-1', '0xdeadbeef', 'step-up-jwt', 'dual-control-jwt').subscribe();

    const req = httpMock.expectOne(`${base}/transfer-1/onchain-handover`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ txHash: '0xdeadbeef' });
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
    req.flush({});
  });

  it('complete() POSTs to the complete sub-path with step-up + dual-control headers', () => {
    service.complete('transfer-1', 'step-up-jwt', 'dual-control-jwt').subscribe();

    const req = httpMock.expectOne(`${base}/transfer-1/complete`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
    req.flush({});
  });

  it('cancel() POSTs the cancellation reason without auth headers', () => {
    service.cancel('transfer-1', 'Requested by issuer').subscribe();

    const req = httpMock.expectOne(`${base}/transfer-1/cancel`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Requested by issuer' });
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });
});
