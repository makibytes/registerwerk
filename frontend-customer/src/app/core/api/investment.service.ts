import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AssetHolder, PageParams, PageResponse } from '../models';

@Injectable({ providedIn: 'root' })
export class InvestmentService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/investments`;

  getMyInvestments(params?: PageParams): Observable<PageResponse<AssetHolder>> {
    const httpParams = this.buildParams(params);
    return this.http.get<PageResponse<AssetHolder>>(this.base, { params: httpParams });
  }

  getInvestment(holderId: string): Observable<AssetHolder> {
    return this.http.get<AssetHolder>(`${this.base}/${holderId}`);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private buildParams(params?: PageParams): HttpParams {
    let httpParams = new HttpParams();
    if (!params) return httpParams;
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        httpParams = httpParams.set(key, String(value));
      }
    });
    return httpParams;
  }
}
