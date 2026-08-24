import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { InvestorLimit } from '../models';

@Injectable({ providedIn: 'root' })
export class InvestorLimitService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/assets`;

  listForAsset(assetId: string): Observable<InvestorLimit[]> {
    return this.http.get<InvestorLimit[]>(`${this.base}/${assetId}/investor-limits`);
  }

  setLimit(assetId: string, investorEntityId: string, body: {
    minInvestmentOverride: number | null;
    maxHoldingOverride: number | null;
    lockupUntil: string | null;
  }): Observable<InvestorLimit> {
    return this.http.put<InvestorLimit>(`${this.base}/${assetId}/investor-limits/${investorEntityId}`, body);
  }

  deleteLimit(assetId: string, investorEntityId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${assetId}/investor-limits/${investorEntityId}`);
  }
}
