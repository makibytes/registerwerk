import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MarketplaceService } from './marketplace.service';
import { environment } from '../../../environments/environment';

describe('MarketplaceService', () => {
  let service: MarketplaceService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/marketplace`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [MarketplaceService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MarketplaceService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads the exact saved manifest from the owner-scoped version URL', () => {
    service.manifest('listing-1', 'version-1').subscribe((response) => {
      expect(response.manifestRaw).toBe('{"slug":"bond-desk"}');
    });

    const req = httpMock.expectOne(`${base}/listings/listing-1/versions/version-1/manifest`);
    expect(req.request.method).toBe('GET');
    req.flush({ manifestRaw: '{"slug":"bond-desk"}' });
  });

  it('sends manifest bytes unchanged with an application/json content type', () => {
    const raw = '{\n  "slug": "bond-desk"\n}';
    service.putManifest('listing-1', 'version-1', raw).subscribe();

    const req = httpMock.expectOne(`${base}/listings/listing-1/versions/version-1/manifest`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('Content-Type')).toBe('application/json');
    expect(req.request.body).toBe(raw);
    req.flush({ valid: true, errors: [], manifestHash: '0xhash' });
  });
});
