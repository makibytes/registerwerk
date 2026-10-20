import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { parseJsonPreservingBigNumbers } from '@registerwerk/ui';
import { environment } from '../../../environments/environment';
import { LendingMarket, LendingPosition, LendingQuote, LendingSupplyPosition } from '../models';
import { LenderEligibility } from '../lending/lender-copy.util';

@Injectable({ providedIn: 'root' })
export class LendingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/lending`;

  listMarkets(status?: string): Observable<LendingMarket[]> {
    const url = status ? `${this.base}/markets?status=${status}` : `${this.base}/markets`;
    return this.getPreservingBigInts<LendingMarket[]>(url);
  }

  getMarket(marketId: string): Observable<LendingMarket> {
    return this.getPreservingBigInts<LendingMarket>(`${this.base}/markets/${marketId}`);
  }

  quote(marketId: string, collateralAmount: string): Observable<LendingQuote> {
    return this.getPreservingBigInts<LendingQuote>(
      `${this.base}/markets/${marketId}/quote?collateralAmount=${collateralAmount}`,
    );
  }

  myPositions(): Observable<LendingPosition[]> {
    return this.getPreservingBigInts<LendingPosition[]>(`${this.base}/my-positions`);
  }

  /** Preflight for the lender side: is the gate enforced (production) and does this entity pass it. */
  lenderEligibility(): Observable<LenderEligibility> {
    return this.http.get<LenderEligibility>(`${this.base}/lender-eligibility`);
  }

  supplyPositions(): Observable<LendingSupplyPosition[]> {
    return this.getPreservingBigInts<LendingSupplyPosition[]>(`${this.base}/supply-positions`);
  }

  /** GETs as raw text and parses and parses it without losing precision on 16+ digit amounts - see parseJsonPreservingBigNumbers in @registerwerk/ui. */
  private getPreservingBigInts<T>(url: string): Observable<T> {
    return this.http
      .get(url, { responseType: 'text' })
      .pipe(map((text) => parseJsonPreservingBigNumbers(text) as T));
  }
}
