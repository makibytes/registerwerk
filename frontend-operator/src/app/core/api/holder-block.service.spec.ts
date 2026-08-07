import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { HolderBlockService } from './holder-block.service';
import { HolderBlockRequest } from '../models';

describe('HolderBlockService', () => {
  let service: HolderBlockService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/holder-blocks`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [HolderBlockService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(HolderBlockService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listAllActive() GETs the active sub-resource', () => {
    service.listAllActive().subscribe();
    const req = httpMock.expectOne(`${base}/active`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('listByEntity() GETs blocks scoped to a legal entity', () => {
    service.listByEntity('entity-1').subscribe();
    const req = httpMock.expectOne(`${base}/entity/entity-1`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('listByWallet() GETs the collection with a walletAddress query param', () => {
    service.listByWallet('0xabc').subscribe();
    const req = httpMock.expectOne(r => r.url === base);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('walletAddress')).toBe('0xabc');
    req.flush([]);
  });

  it('create() POSTs the block request with step-up + dual-control headers', () => {
    const body: HolderBlockRequest = {
      walletAddress: '0xabc',
      entityId: 'entity-1',
      blockType: 'REGULATORISCH',
      legalBasis: 'Sanctions hit under review',
    };
    service.create(body, 'step-up-jwt', 'dual-control-jwt').subscribe();

    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
    req.flush({});
  });

  it('lift() POSTs the reason to the lift sub-path with step-up + dual-control headers', () => {
    service.lift('block-1', 'False positive cleared', 'step-up-jwt', 'dual-control-jwt').subscribe();

    const req = httpMock.expectOne(`${base}/block-1/lift`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'False positive cleared' });
    expect(req.request.headers.get('Authorization')).toBe('Bearer step-up-jwt');
    expect(req.request.headers.get('X-Dual-Control-Token')).toBe('dual-control-jwt');
    req.flush({});
  });
});
