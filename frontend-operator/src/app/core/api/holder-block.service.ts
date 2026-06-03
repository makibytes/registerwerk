import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { HolderBlock, HolderBlockRequest } from '../models';

@Injectable({ providedIn: 'root' })
export class HolderBlockService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/holder-blocks`;

  listAllActive(): Observable<HolderBlock[]> {
    return this.http.get<HolderBlock[]>(`${this.base}/active`);
  }

  listByEntity(entityId: string): Observable<HolderBlock[]> {
    return this.http.get<HolderBlock[]>(`${this.base}/entity/${entityId}`);
  }

  listByWallet(walletAddress: string): Observable<HolderBlock[]> {
    return this.http.get<HolderBlock[]>(`${this.base}`, { params: { walletAddress } });
  }

  create(
    body: HolderBlockRequest,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<HolderBlock> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<HolderBlock>(this.base, body, { headers });
  }

  lift(
    id: string,
    reason: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<HolderBlock> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<HolderBlock>(`${this.base}/${id}/lift`, { reason }, { headers });
  }
}
