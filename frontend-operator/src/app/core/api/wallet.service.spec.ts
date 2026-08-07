import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { WalletService } from './wallet.service';

describe('WalletService', () => {
  let service: WalletService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/admin/wallets`;
  const defaultsBase = `${environment.apiUrl}/admin/wallet-defaults`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [WalletService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(WalletService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listWallets() GETs the wallets collection', () => {
    service.listWallets().subscribe();
    const req = httpMock.expectOne(base);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('generate() POSTs name and type to the generate sub-path', () => {
    service.generate('Treasury EVM Wallet', 'EVM').subscribe();
    const req = httpMock.expectOne(`${base}/generate`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Treasury EVM Wallet', type: 'EVM' });
    req.flush({});
  });

  it('importRaw() includes partyId and jwt only when provided', () => {
    service.importRaw('Canton Wallet', 'CANTON', '0xkey', 'party-1', 'jwt-1').subscribe();
    const req = httpMock.expectOne(`${base}/import-raw`);
    expect(req.request.body).toEqual({ name: 'Canton Wallet', type: 'CANTON', privateKey: '0xkey', partyId: 'party-1', jwt: 'jwt-1' });
    req.flush({});
  });

  it('importRaw() omits partyId/jwt when not provided', () => {
    service.importRaw('EVM Wallet', 'EVM', '0xkey').subscribe();
    const req = httpMock.expectOne(`${base}/import-raw`);
    expect(req.request.body).toEqual({ name: 'EVM Wallet', type: 'EVM', privateKey: '0xkey' });
    req.flush({});
  });

  it('importKeystore() sends multipart FormData with name, password, and file', () => {
    const file = new File(['{}'], 'keystore.json');
    service.importKeystore('Imported Wallet', 'secret', file).subscribe();

    const req = httpMock.expectOne(`${base}/import-keystore`);
    expect(req.request.method).toBe('POST');
    const form = req.request.body as FormData;
    expect(form.get('name')).toBe('Imported Wallet');
    expect(form.get('password')).toBe('secret');
    expect((form.get('file') as File).name).toBe('keystore.json');
    req.flush({});
  });

  it('exportKeystore() POSTs the password and expects a blob response', () => {
    service.exportKeystore('wallet-1', 'secret').subscribe();
    const req = httpMock.expectOne(`${base}/wallet-1/export-keystore`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ password: 'secret' });
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob());
  });

  it('exportRaw() POSTs to the confirm=true export-raw sub-path and expects a text response', () => {
    service.exportRaw('wallet-1').subscribe();
    const req = httpMock.expectOne(`${base}/wallet-1/export-raw?confirm=true`);
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');
    req.flush('0xrawkey');
  });

  it('rename() PATCHes the wallet name', () => {
    service.rename('wallet-1', 'New Name').subscribe();
    const req = httpMock.expectOne(`${base}/wallet-1`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ name: 'New Name' });
    req.flush({});
  });

  it('delete() DELETEs the wallet resource', () => {
    service.delete('wallet-1').subscribe();
    const req = httpMock.expectOne(`${base}/wallet-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('getById() GETs a single wallet', () => {
    service.getById('wallet-1').subscribe();
    const req = httpMock.expectOne(`${base}/wallet-1`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('getBalances() GETs the balances sub-resource', () => {
    service.getBalances('wallet-1').subscribe();
    const req = httpMock.expectOne(`${base}/wallet-1/balances`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('listDefaults() GETs the wallet-defaults collection', () => {
    service.listDefaults().subscribe();
    const req = httpMock.expectOne(defaultsBase);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('setDefault() PUTs the walletId for a given chain', () => {
    service.setDefault('chain-1', 'wallet-1').subscribe();
    const req = httpMock.expectOne(`${defaultsBase}/chain-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ walletId: 'wallet-1' });
    req.flush({});
  });
});
