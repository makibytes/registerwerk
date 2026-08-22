import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { BackendPageResponse, TokenTransferResponse } from '../models';

/**
 * On-chain transfer history for an asset — `GET /api/v1/assets/{assetId}/history`
 * (`indexer.web.TokenHistoryController`). Investors/issuers reach this via
 * `@assetAccessChecker.canRead`; the backend resolves `finalityLabel` to plain language for this
 * app's roles server-side (see `TokenTransferMapper`) — never render `finalityStatus` directly
 * here, it's the technical enum name.
 */
@Injectable({ providedIn: 'root' })
export class TokenHistoryService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/assets`;

  getAssetHistory(assetId: string, page = 0, size = 20): Observable<BackendPageResponse<TokenTransferResponse>> {
    const params = new HttpParams().set('page', page).set('size', size).set('sort', 'occurredAt,desc');
    return this.http.get<BackendPageResponse<TokenTransferResponse>>(`${this.base}/${assetId}/history`, { params });
  }
}
