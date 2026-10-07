import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AssetSlot } from '../models';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

@Injectable({ providedIn: 'root' })
export class SlotService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}`;

  getSlots(deploymentId: string): Observable<AssetSlot[]> {
    return this.http.get<AssetSlot[]>(`${this.base}/deployments/${deploymentId}/slots`);
  }

  /** Step-up + second approver (`ERC3525_SLOT_CREATE`): the slot carries its supply cap. */
  createSlot(deploymentId: string, body: {
    slotId: string;
    name?: string;
    metadata?: Record<string, unknown>;
    supplyCap?: string;
  }, tokens: DualControlTokens): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/slots`, body,
      { headers: dualControlHeaders(tokens) });
  }

  pauseSlot(deploymentId: string, slotId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/slots/${slotId}/pause`, {});
  }

  unpauseSlot(deploymentId: string, slotId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/slots/${slotId}/unpause`, {});
  }

  /** Step-up + second approver (`ERC3525_SLOT_MINT`). */
  mintIntoSlot(deploymentId: string, slotId: string, body: { toAddress: string; value: string },
               tokens: DualControlTokens): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/slots/${slotId}/mint`, body,
      { headers: dualControlHeaders(tokens) });
  }

  freezeToken(deploymentId: string, tokenId: string, reason: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/tokens/${tokenId}/freeze`, { reason });
  }

  unfreezeToken(deploymentId: string, tokenId: string): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/tokens/${tokenId}/unfreeze`, {});
  }

  /** Step-up + second approver (`ERC3525_FORCED_VALUE_TRANSFER_EWG24`); values are decimal strings. */
  forcedValueTransfer(deploymentId: string, tokenId: string, body: {
    toTokenId: string;
    value: string;
    legalBasis: string;
  }, tokens: DualControlTokens): Observable<{ txId: string }> {
    return this.http.post<{ txId: string }>(`${this.base}/deployments/${deploymentId}/tokens/${tokenId}/forced-value-transfer`, body,
      { headers: dualControlHeaders(tokens) });
  }
}
