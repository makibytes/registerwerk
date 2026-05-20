import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AssetSlot } from '../models';

@Injectable({ providedIn: 'root' })
export class SlotService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}`;

  getSlots(deploymentId: string): Observable<AssetSlot[]> {
    return this.http.get<AssetSlot[]>(`${this.base}/deployments/${deploymentId}/slots`);
  }

  createSlot(deploymentId: string, body: {
    slotId: string;
    name?: string;
    metadata?: Record<string, unknown>;
    supplyCap?: string;
  }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/slots`, body);
  }

  pauseSlot(deploymentId: string, slotId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/slots/${slotId}/pause`, {});
  }

  unpauseSlot(deploymentId: string, slotId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/slots/${slotId}/unpause`, {});
  }

  mintIntoSlot(deploymentId: string, slotId: string, body: { toAddress: string; value: string }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/slots/${slotId}/mint`, body);
  }

  freezeToken(deploymentId: string, tokenId: string, reason: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/tokens/${tokenId}/freeze`, { reason });
  }

  unfreezeToken(deploymentId: string, tokenId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/tokens/${tokenId}/unfreeze`, {});
  }

  forcedValueTransfer(deploymentId: string, tokenId: string, body: {
    toTokenId: string;
    value: string;
    legalBasis: string;
  }): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/tokens/${tokenId}/forced-value-transfer`, body);
  }
}
