import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LendingMarket, LendingPosition, LendingQuote, LendingSupplyPosition } from '../models';
import { parseJsonPreservingBigInts } from './json-bigint.util';

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

  supplyPositions(): Observable<LendingSupplyPosition[]> {
    return this.getPreservingBigInts<LendingSupplyPosition[]>(`${this.base}/supply-positions`);
  }

  /** GETs as raw text and parses with a bigint-safe reviver — see json-bigint.util.ts. */
  private getPreservingBigInts<T>(url: string): Observable<T> {
    return this.http
      .get(url, { responseType: 'text' })
      .pipe(map((text) => parseJsonPreservingBigInts(text) as T));
  }
}
