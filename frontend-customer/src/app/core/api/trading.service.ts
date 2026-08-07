import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CompanyTraderSettings,
  TradeExecution,
  TradeListing,
  TradingOffer,
  TradingVenue,
  SellableHolding,
} from '../models';

@Injectable({ providedIn: 'root' })
export class TradingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/trading`;

  listVenues(): Observable<TradingVenue[]> {
    return this.http.get<TradingVenue[]>(`${this.base}/venues`);
  }

  getSettings(): Observable<CompanyTraderSettings> {
    return this.http.get<CompanyTraderSettings>(`${this.base}/settings`);
  }

  saveSettings(body: CompanyTraderSettings): Observable<CompanyTraderSettings> {
    return this.http.put<CompanyTraderSettings>(`${this.base}/settings`, body);
  }

  listSellableHoldings(): Observable<SellableHolding[]> {
    return this.http.get<SellableHolding[]>(`${this.base}/sellable-holdings`);
  }

  listCompanyListings(): Observable<TradeListing[]> {
    return this.http.get<TradeListing[]>(`${this.base}/listings`);
  }

  createListing(body: {
    holderId: string;
    quantity: number;
    pricePerUnit: number;
    useCompanyDefaultPaymentOption: boolean;
    allowedPaymentOptions: string[];
  }): Observable<TradeListing> {
    return this.http.post<TradeListing>(`${this.base}/listings`, body);
  }

  cancelListing(listingId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/listings/${listingId}`);
  }

  listMarketplaceOffers(): Observable<TradingOffer[]> {
    return this.http.get<TradingOffer[]>(`${this.base}/marketplace`);
  }

  buy(listingId: string, body: {
    quantity: number;
    orderType: string;
    limitPrice?: number | null;
    paymentOption?: string | null;
    walletPreferenceMode?: string | null;
    endpointId?: string | null;
    walletAddress?: string | null;
  }): Observable<TradeExecution> {
    return this.http.post<TradeExecution>(`${this.base}/marketplace/${listingId}/buy`, body);
  }

  listHistory(): Observable<TradeExecution[]> {
    return this.http.get<TradeExecution[]>(`${this.base}/history`);
  }

  /** Buyer declares payment on a PENDING trade. This does NOT settle the trade — it moves to
   *  AWAITING_SELLER_CONFIRMATION and waits for the seller to independently confirm receipt. */
  declarePayment(executionId: string, paymentReference: string): Observable<TradeExecution> {
    return this.http.post<TradeExecution>(`${this.base}/history/${executionId}/settle`, { paymentReference });
  }

  /** Seller confirms receipt of the buyer's declared payment — this is what actually credits
   *  the register. */
  confirmPayment(executionId: string): Observable<TradeExecution> {
    return this.http.post<TradeExecution>(`${this.base}/history/${executionId}/confirm-payment`, {});
  }

  /** Seller disputes the buyer's declared payment (claims it was never received). */
  disputePayment(executionId: string, reason: string): Observable<TradeExecution> {
    return this.http.post<TradeExecution>(`${this.base}/history/${executionId}/dispute-payment`, { reason });
  }

  cancelTrade(executionId: string, reason: string): Observable<TradeExecution> {
    return this.http.post<TradeExecution>(`${this.base}/history/${executionId}/cancel`, { reason });
  }

  downloadConfirmation(executionId: string): Observable<Blob> {
    return this.http.get(`${this.base}/history/${executionId}/confirmation`, { responseType: 'blob' });
  }

  downloadIso20022Confirmation(executionId: string): Observable<Blob> {
    return this.http.get(`${this.base}/history/${executionId}/confirmation/iso20022`, { responseType: 'blob' });
  }
}
