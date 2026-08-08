import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TradingService } from './trading.service';
import { environment } from '../../../environments/environment';

describe('TradingService', () => {
  let service: TradingService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiUrl}/trading`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [TradingService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TradingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listVenues() GETs /trading/venues', () => {
    service.listVenues().subscribe();
    const req = httpMock.expectOne(`${base}/venues`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('saveSettings() PUTs the full settings body', () => {
    const body = { defaultPaymentOptionId: 'sepa', autoAcceptOffers: false } as never;
    service.saveSettings(body).subscribe();
    const req = httpMock.expectOne(`${base}/settings`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(body);
  });

  it('createListing() POSTs the listing payload to /trading/listings', () => {
    const body = {
      holderId: 'holder-1',
      quantity: 100,
      pricePerUnit: 1.05,
      useCompanyDefaultPaymentOption: true,
      allowedPaymentOptions: ['sepa'],
    };
    service.createListing(body).subscribe();
    const req = httpMock.expectOne(`${base}/listings`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('cancelListing() DELETEs the listing-scoped URL', () => {
    service.cancelListing('listing-1').subscribe();
    const req = httpMock.expectOne(`${base}/listings/listing-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('buy() POSTs the order payload to the listing-scoped buy URL', () => {
    const body = {
      quantity: 10,
      orderType: 'MARKET',
      limitPrice: null,
      paymentOption: 'sepa',
      walletPreferenceMode: null,
      endpointId: null,
      walletAddress: null,
    };
    service.buy('listing-1', body).subscribe();
    const req = httpMock.expectOne(`${base}/marketplace/listing-1/buy`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('declarePayment() POSTs the buyer payment reference to the execution-scoped settle URL', () => {
    service.declarePayment('exec-1', 'SEPA-123').subscribe();
    const req = httpMock.expectOne(`${base}/history/exec-1/settle`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ paymentReference: 'SEPA-123' });
    req.flush({});
  });

  it('confirmPayment() POSTs an empty body to the seller confirmation URL', () => {
    service.confirmPayment('exec-1').subscribe();
    const req = httpMock.expectOne(`${base}/history/exec-1/confirm-payment`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({});
  });

  it('listHistory() GETs /trading/history', () => {
    service.listHistory().subscribe();
    const req = httpMock.expectOne(`${base}/history`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
